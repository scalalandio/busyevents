package io.scalaland.busyevents
package aws
package kinesis

import cats.effect.{ Async, Resource, Sync, Timer }
import cats.implicits._
import io.scalaland.busyevents.utils.FutureToAsync
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.kinesis.model.{
  CreateStreamRequest,
  DeleteStreamRequest,
  PutRecordsRequest,
  PutRecordsRequestEntry
}
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.kinesis.common.{ ConfigsBuilder, InitialPositionInStream, InitialPositionInStreamExtended }
import software.amazon.kinesis.coordinator.{ Scheduler, SchedulerCoordinatorFactory }
import software.amazon.kinesis.lifecycle.events.{
  InitializationInput,
  LeaseLostInput,
  ProcessRecordsInput,
  ShardEndedInput,
  ShutdownRequestedInput
}
import software.amazon.kinesis.metrics.MetricsLevel
import software.amazon.kinesis.processor.ShardRecordProcessor
import software.amazon.kinesis.retrieval.polling.PollingConfig
import software.amazon.kinesis.retrieval.{ KinesisClientRecord, RetrievalConfig }

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.JavaConverters._
import scala.compat.java8.FutureConverters._

trait KinesisBusTestProvider extends BusTestProvider with AWSTestProvider {

  /// name for specification descriptions
  override def busImplementationName = "Kinesis"

  private def kinesisEndpoint  = DockerServices.kinesis
  private def dynamoEndpoint   = DockerServices.dynamoDB
  private def appName          = s"kinesis-test-$providerId"
  private def streamName       = s"kinesis-bus-$providerId"
  private def dynamoTableName  = s"kinesis-bus-check-$providerId"
  private def dynamoTableName2 = s"helper-kinesis-bus-check-$providerId"
  private def kinesisConfig: ClientConfig[KinesisAsyncClient]  = testConfig(kinesisEndpoint)
  private def dynamoConfig:  ClientConfig[DynamoDbAsyncClient] = testConfig(dynamoEndpoint)

  /// type used in tests
  override type BusEnvelope = KinesisEnvelope

  // implementations

  override def busEnveloper: Enveloper[BusEnvelope] = kinesisEnveloper(_ => "same-key")
  override def busExtractor: Extractor[BusEnvelope] = kinesisExtractor

  private var initialized = false
  private var kinesisAsyncClient: KinesisAsyncClient        = _
  private var fetchedRecords:     List[KinesisClientRecord] = List.empty[KinesisClientRecord]

  private def kinesisResource[F[_]: Async] =
    loggedResourceFrom(s"KinesisAsyncClient to $kinesisEndpoint")(AWSResources.kinesis[F](kinesisConfig))
      .map { kinesis =>
        kinesisAsyncClient = kinesis
        kinesis
      }
      .flatTap { kinesis =>
        loggedResource(s"Kinesis stream '$streamName'") {
          Async[F].defer {
            kinesis
              .createStream(CreateStreamRequest.builder().streamName(streamName).shardCount(1).build())
              .toScala
              .asAsync[F]
          }
        } { _ =>
          Async[F].defer {
            kinesis.deleteStream(DeleteStreamRequest.builder().streamName(streamName).build()).toScala.asAsync[F]
          }.void
        }
      }

  private def dynamoDBResource[F[_]: Async] =
    loggedResourceFrom(s"DynamoDbAsyncClient to $dynamoEndpoint")(AWSResources.dynamo[F](dynamoConfig))
      .flatTap { dynamo =>
        loggedResource(s"DynamoDB table '$dynamoTableName'") {
          ().pure[F] // table is created by a consumer thread
        } { _ =>
          Async[F].defer {
            dynamo.deleteTable(DeleteTableRequest.builder().tableName(dynamoTableName).build()).toScala.asAsync[F]
          }.void
        }
      }
      .flatTap { dynamo =>
        loggedResource(s"DynamoDB table '$dynamoTableName2'") {
          ().pure[F] // table is created by a consumer thread
        } { _ =>
          Async[F].defer {
            dynamo.deleteTable(DeleteTableRequest.builder().tableName(dynamoTableName2).build()).toScala.asAsync[F]
          }.void
        }
      }

  private def cloudwatchResource[F[_]: Async] =
    loggedResourceFrom("CloudWatchAsyncClient")(AWSResources.cloudWatch[F]())

  private class TestRecordProcessor extends ShardRecordProcessor {
    override def initialize(initializationInput: InitializationInput) =
      initialized = true
    override def processRecords(processRecordsInput: ProcessRecordsInput) =
      fetchedRecords = fetchedRecords ++ processRecordsInput.records().asScala
    override def leaseLost(leaseLostInput:   LeaseLostInput) = ()
    override def shardEnded(shardEndedInput: ShardEndedInput) =
      shardEndedInput.checkpointer.checkpoint()
    override def shutdownRequested(shutdownRequestedInput: ShutdownRequestedInput) =
      shutdownRequestedInput.checkpointer.checkpoint()
  }

  private def scheduler[F[_]: Async] = (kinesisResource[F], dynamoDBResource[F], cloudwatchResource[F]).tupled.flatMap {
    case (kinesis, dynamo, cloudwatch) =>
      loggedResource("Kinesis Scheduler") {
        Async[F].delay {
          val schedulerConfig = new ConfigsBuilder(
            streamName,
            appName,
            kinesis,
            dynamo,
            cloudwatch,
            dynamoTableName2,
            () => new TestRecordProcessor
          ).tableName(dynamoTableName2)

          val pollingConfig: PollingConfig =
            new PollingConfig(streamName, kinesis)
          val retrievalConfig: RetrievalConfig =
            new RetrievalConfig(kinesisAsyncClient, streamName, appName)
              .initialPositionInStreamExtended(
                InitialPositionInStreamExtended.newInitialPosition(InitialPositionInStream.TRIM_HORIZON)
              )
              .retrievalSpecificConfig(pollingConfig)

          new Scheduler(
            schedulerConfig.checkpointConfig,
            schedulerConfig.coordinatorConfig
              .parentShardPollIntervalMillis(100L)
              .coordinatorFactory(new SchedulerCoordinatorFactory),
            schedulerConfig.leaseManagementConfig,
            schedulerConfig.lifecycleConfig,
            schedulerConfig.metricsConfig.metricsLevel(MetricsLevel.NONE),
            schedulerConfig.processorConfig,
            retrievalConfig
          )
        }
      } { s =>
        Async[F].delay(s.shutdown())
      }
  }

  private def consumerThread[F[_]: Async: Timer] = scheduler[F].flatMap { s =>
    loggedResource("Kinesis Consumer thread") {
      val schedulerThread = new Thread(s)
      schedulerThread.setDaemon(true)
      schedulerThread.start()
      10.tailRecM[F, Thread] { countdown: Int =>
        log.info("attempt to wait for thread")
        if (initialized) schedulerThread.asRight[Int].pure[F]
        else if (countdown >= 0 && !initialized) (Timer[F].sleep(1.second) *> (countdown - 1).asLeft[Thread].pure[F])
        else
          Sync[F].delay(schedulerThread.interrupt()) *>
            new Exception("Unable to initialize scheduler thread").raiseError[F, Either[Int, Thread]]
      }
    } { schedulerThread =>
      Async[F].delay(schedulerThread.interrupt())
    }
  }

  override def busEnvironment[F[_]:  Async: Timer]: Resource[F, Unit] = consumerThread[F].void
  override def busConfigurator[F[_]: Sync]: Resource[F, EventBus.BusConfigurator[KinesisEnvelope]] =
    loggedResourceFrom("KinesisBusConfigurator") {
      (
        loggedResourceFrom(s"KinesisAsyncClient2 to $kinesisEndpoint")(AWSResources.kinesis[F](kinesisConfig)),
        loggedResourceFrom(s"DynamoDBAsyncClient2 to $dynamoEndpoint")(AWSResources.dynamo[F](dynamoConfig)),
        loggedResourceFrom("CloudWatchAsyncClient2")(AWSResources.cloudWatch[F]())
      ).mapN(KinesisBusConfigurator(KinesisBusConfig(appName, streamName, dynamoTableName), log))
    }

  // test utilities

  override def isSafeForPublishing(msgSizes: Seq[Long]): Boolean =
    (msgSizes.length <= 500L) && msgSizes.forall(_ <= (1024L * 1024L)) && (msgSizes.sum <= (5L * 1024L * 1024L))
  override def busPublishDirectly[F[_]: Async](envelope: List[BusEnvelope]): F[Unit] =
    Async[F].defer {
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
    }.void
  override def busFetchNotProcessedDirectly[F[_]: Async](): F[List[BusEnvelope]] = Async[F].delay {
    fetchedRecords.map(r => KinesisEnvelope(r.partitionKey(), r.data(), None))
  }
  override def busMarkAllAsProcessed[F[_]: Async]: F[Unit] = Async[F].delay {
    fetchedRecords = List.empty
  }
}
