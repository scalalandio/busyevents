package io.scalaland.busyevents
package aws
package kinesis

import java.util.UUID

import cats.effect.{ Async, Resource, Sync, Timer }
import cats.implicits._
import io.scalaland.busyevents.utils.FutureToAsync
import px.kinesis.stream.consumer.checkpoint.CheckpointConfig
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.kinesis.model._
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType._
import software.amazon.kinesis.common._
import software.amazon.kinesis.coordinator.{ Scheduler, SchedulerCoordinatorFactory }
import software.amazon.kinesis.leases.exceptions.InvalidStateException
import software.amazon.kinesis.lifecycle.events._
import software.amazon.kinesis.metrics.MetricsLevel
import software.amazon.kinesis.processor.{ Checkpointer, ShardRecordProcessor }
import software.amazon.kinesis.retrieval.kpl.ExtendedSequenceNumber
import software.amazon.kinesis.retrieval.RetrievalConfig
import software.amazon.kinesis.retrieval.polling.PollingConfig

import scala.collection.JavaConverters._
import scala.compat.java8.FutureConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Try

trait KinesisBusTestProvider extends BusTestProvider with AWSTestProvider {

  /// name for specification descriptions
  override def busImplementationName = "Kinesis"

  private def kinesisEndpoint = DockerServices.kinesis
  private def dynamoEndpoint  = DockerServices.dynamoDB
  private def appName         = s"kinesis-test-$providerId"
  private def streamName      = s"kinesis-bus-$providerId"
  private def dynamoTableName = s"kinesis-bus-check-$providerId"
  private def kinesisConfig: ClientConfig[KinesisAsyncClient]  = testConfig(kinesisEndpoint)
  private def dynamoConfig:  ClientConfig[DynamoDbAsyncClient] = testConfig(dynamoEndpoint)

  /// type used in tests
  override type BusEnvelope = KinesisEnvelope

  // implementations

  override def busEnveloper: Enveloper[BusEnvelope] = kinesisEnveloper(_ => "same-key")
  override def busExtractor: Extractor[BusEnvelope] = kinesisExtractor

  private var kinesisAsyncClient: KinesisAsyncClient = _
  private def kinesisResource[F[_]: Async] =
    loggedResourceFrom(s"KinesisAsyncClient to $kinesisEndpoint")(AWSResources.kinesis[F](kinesisConfig))
      .map { kinesis =>
        kinesisAsyncClient = kinesis
        kinesis
      }
      .flatTap { kinesis =>
        loggedResource(s"Kinesis stream '$streamName'") {
          kinesis
            .createStream(CreateStreamRequest.builder().streamName(streamName).shardCount(1).build())
            .toScala
            .asAsync[F]
        } { _ =>
          kinesis.deleteStream(DeleteStreamRequest.builder().streamName(streamName).build()).toScala.asAsync[F].void
        }
      }

  private var dynamoDBAsyncClient: DynamoDbAsyncClient = _
  private def dynamoDBResource[F[_]: Async] =
    loggedResourceFrom(s"DynamoDbAsyncClient to $dynamoEndpoint")(AWSResources.dynamo[F](dynamoConfig))
      .map { dynamo =>
        dynamoDBAsyncClient = dynamo
        dynamo
      }
      .flatTap { dynamo =>
        loggedResource(s"DynamoDB table '$dynamoTableName'") {
          ().pure[F] // table is created by a consumer thread
        } { _ =>
          dynamo.deleteTable(DeleteTableRequest.builder().tableName(dynamoTableName).build()).toScala.asAsync[F].void
        }
      }

  private var cloudwatchAsyncClient: CloudWatchAsyncClient = _
  private def cloudwatchResource[F[_]: Async] =
    loggedResourceFrom("CloudWatchAsyncClient")(AWSResources.cloudWatch[F]()).map { cloudwatch =>
      cloudwatchAsyncClient = cloudwatch
      cloudwatch
    }

  private class TestRecordProcessor extends ShardRecordProcessor {
    override def initialize(initializationInput:           InitializationInput)    = ()
    override def processRecords(processRecordsInput:       ProcessRecordsInput)    = ()
    override def leaseLost(leaseLostInput:                 LeaseLostInput)         = ()
    override def shardEnded(shardEndedInput:               ShardEndedInput)        = ()
    override def shutdownRequested(shutdownRequestedInput: ShutdownRequestedInput) = ()
  }

  private def checkpointerResource[F[_]: Sync: Timer](
    kinesis:             KinesisAsyncClient,
    dynamo:              DynamoDbAsyncClient,
    cloudwatch:          CloudWatchAsyncClient,
    shouldStartConsumer: Boolean
  ): Resource[F, (Checkpointer, String => Option[String])] =
    loggedResource("Kinesis Checkpointer") {
      Sync[F].delay {
        val uniqueWorkerID = s"checkpointer-${UUID.randomUUID()}"

        val schedulerConfig =
          new ConfigsBuilder(
            streamName,
            appName,
            kinesis,
            dynamo,
            cloudwatch,
            uniqueWorkerID,
            () => new TestRecordProcessor
          ).tableName(dynamoTableName)

        val pollingConfig: PollingConfig = new PollingConfig(streamName, kinesis)
        val retrievalConfig: RetrievalConfig = new RetrievalConfig(kinesis, streamName, appName)
          .initialPositionInStreamExtended(
            InitialPositionInStreamExtended.newInitialPosition(InitialPositionInStream.TRIM_HORIZON)
          )
          .retrievalSpecificConfig(pollingConfig)

        new Scheduler(
          schedulerConfig.checkpointConfig,
          schedulerConfig.coordinatorConfig
            .parentShardPollIntervalMillis(500)
            .coordinatorFactory(new SchedulerCoordinatorFactory),
          schedulerConfig.leaseManagementConfig,
          schedulerConfig.lifecycleConfig,
          schedulerConfig.metricsConfig.metricsLevel(MetricsLevel.NONE),
          schedulerConfig.processorConfig,
          retrievalConfig
        )
      }
    } { scheduler: Scheduler =>
      if (shouldStartConsumer) Sync[F].delay(scheduler.shutdown()) // ignore error as it's used after manual commit
      else ().pure[F]
    }.map { s: Scheduler =>
      if (shouldStartConsumer) {
        val thread = new Thread(s, "CheckpointerScheduler")
        thread.setDaemon(true)
        thread.start()
      }

      val checkpoint = s.checkpoint()
      val concurrenctToken = (shardId: String) =>
        Try(
          s.shardInfoShardConsumerMap.keySet.asScala.toList.find(_.shardId == shardId).map(_.concurrencyToken)
        ).toOption.flatten
      checkpoint -> concurrenctToken
    }

  override def busEnvironment[F[_]:  Async: Timer]: Resource[F, Unit] = Resource.pure[F, Unit](())
  override def busConfigurator[F[_]: Async]: Resource[F, EventBus.BusConfigurator[KinesisEnvelope]] =
    loggedResourceFrom("KinesisBusConfigurator") {
      (
        kinesisResource[F],
        dynamoDBResource[F],
        cloudwatchResource[F]
      ).mapN(
        KinesisBusConfigurator(
          KinesisBusConfig(appName, streamName, dynamoTableName),
          log,
          checkpointConfig = CheckpointConfig(completionTimeout = 5.seconds,
                                              timeout              = 5.seconds,
                                              maxBufferSize        = 1,
                                              maxDurationInSeconds = 1)
        )
      )
    }

  // test utilities

  override def isSafeForPublishing(msgSizes: Seq[Long]): Boolean =
    (msgSizes.length <= 500L) && msgSizes.forall(_ <= (1024L * 1024L)) && (msgSizes.sum <= (5L * 1024L * 1024L))
  override def busPublishDirectly[F[_]: Async](envelope: List[BusEnvelope]): F[Unit] =
    kinesisAsyncClient
      .putRecords(
        PutRecordsRequest
          .builder()
          .streamName(streamName)
          .records(
            envelope.map { e =>
              PutRecordsRequestEntry.builder().partitionKey(e.key).data(SdkBytes.fromByteBuffer(e.byteBuffer)).build()
            }.asJavaCollection
          )
          .build()
      )
      .toScala
      .asAsync[F]
      .void
  private def busReadAllWithLastSequence[F[_]: Async](checkpointer: Checkpointer) =
    for {
      firstShardId <- kinesisAsyncClient
        .listShards(ListShardsRequest.builder().streamName(streamName).build())
        .toScala
        .asAsync[F]
        .map(_.shards.get(0).shardId)
      sequenceNumberOpt <- Async[F].delay(checkpointer.getCheckpoint(firstShardId)).map(Option(_)).recover {
        case _ => None
      }
      initialShardIterator <- kinesisAsyncClient
        .getShardIterator(
          (sequenceNumberOpt match {
            // this is absurd, but it works :/
            case Some(esn) if esn.sequenceNumber.forall(_.isDigit) =>
              GetShardIteratorRequest
                .builder()
                .shardIteratorType(AFTER_SEQUENCE_NUMBER)
                .startingSequenceNumber(esn.sequenceNumber)
            case Some(esn) if Set(TRIM_HORIZON.name, LATEST.name).contains(esn.sequenceNumber) =>
              GetShardIteratorRequest.builder().shardIteratorType(esn.sequenceNumber)
            case _ =>
              GetShardIteratorRequest.builder().shardIteratorType(TRIM_HORIZON)
          }).streamName(streamName).shardId(firstShardId).build()
        )
        .toScala
        .asAsync[F]
        .map(_.shardIterator())
        .map(Option(_))
      (esn, records) <- (initialShardIterator -> List.empty[Record])
        .tailRecM[F, (ExtendedSequenceNumber, List[BusEnvelope])] {
          case (Some(iterator), records) =>
            kinesisAsyncClient
              .getRecords(GetRecordsRequest.builder().shardIterator(iterator).limit(10000).build())
              .toScala
              .asAsync[F]
              .map { result =>
                (Option(result.nextShardIterator())
                  .filter(_ => result.records.isEmpty) -> (records ++ result.records.asScala))
                  .asLeft[(ExtendedSequenceNumber, List[BusEnvelope])]
              }
          case (None, records) =>
            val esn = records.lastOption
              .map(_.sequenceNumber)
              .map(new ExtendedSequenceNumber(_))
              .orElse(sequenceNumberOpt)
              .getOrElse(ExtendedSequenceNumber.TRIM_HORIZON)
            val enveloped = records.map(KinesisEnvelope.fromKinesisRecord)
            (esn -> enveloped).asRight[(Option[String], List[Record])].pure[F]
        }
    } yield (firstShardId, esn, records)
  override def busFetchNotProcessedDirectly[F[_]: Async: Timer]: F[List[BusEnvelope]] =
    checkpointerResource[F](kinesisAsyncClient, dynamoDBAsyncClient, cloudwatchAsyncClient, shouldStartConsumer = false)
      .use {
        case (checkpoint, _) =>
          busReadAllWithLastSequence[F](checkpoint)
            .map {
              case (_, _, records) => records
            }
            .recover {
              case _: InvalidStateException => List.empty[BusEnvelope]
            }
      }
  override def busMarkAllAsProcessed[F[_]: Async: Timer]: F[Unit] =
    checkpointerResource[F](kinesisAsyncClient, dynamoDBAsyncClient, cloudwatchAsyncClient, shouldStartConsumer = true)
      .use {
        case (checkpoint, concurrencyToken) =>
          busReadAllWithLastSequence[F](checkpoint).flatMap {
            case (shardId, extendedSequenceNumber, _) =>
              60.tailRecM[F, Unit] { attempts =>
                if (attempts >= 0) {
                  concurrencyToken(shardId) match {
                    case Some(token) =>
                      checkpoint.operation("MARK_ALL_PROCESSED")
                      checkpoint.setCheckpoint(shardId, extendedSequenceNumber, token).asRight[Int].pure[F]
                    case None =>
                      Timer[F].sleep(1.second) *> (attempts - 1).asLeft[Unit].pure[F]
                  }
                } else ().asRight[Int].pure[F] // no writes, no need to acquire lease and update state
              }
          }
      }

}
