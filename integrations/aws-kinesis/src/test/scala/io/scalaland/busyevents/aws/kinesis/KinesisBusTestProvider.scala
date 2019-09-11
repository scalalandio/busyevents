package io.scalaland.busyevents
package aws
package kinesis

import cats.effect.{ Async, Resource, Sync }
import cats.implicits._
import io.scalaland.busyevents.utils.FutureToAsync
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.model.{ CreateTableRequest, DeleteTableRequest }
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
    AWSResources
      .kinesis[F](kinesisConfig)
      .map { kinesis =>
        kinesisAsyncClient = kinesis
        kinesis
      }
      .flatMap { kinesis =>
        Resource.make[F, KinesisAsyncClient] {
          Async[F].defer {
            kinesis
              .createStream(CreateStreamRequest.builder().streamName(streamName).shardCount(1).build())
              .toScala
              .asAsync
          } *> kinesis.pure[F]
        } { _ =>
          Async[F].defer {
            kinesis.deleteStream(DeleteStreamRequest.builder().streamName(streamName).build()).toScala.asAsync
          }.void
        }
      }

  private def dynamoDBResource[F[_]: Async] =
    AWSResources.dynamo[F](dynamoConfig).flatMap { dynamo =>
      Resource.make[F, DynamoDbAsyncClient] {
        Async[F].defer {
          dynamo.createTable(CreateTableRequest.builder().tableName(dynamoTableName).build()).toScala.asAsync *>
            dynamo.createTable(CreateTableRequest.builder().tableName(dynamoTableName2).build()).toScala.asAsync
        } *> dynamo.pure[F]
      } { _ =>
        Async[F].defer {
          dynamo.deleteTable(DeleteTableRequest.builder().tableName(dynamoTableName2).build()).toScala.asAsync *>
            dynamo.deleteTable(DeleteTableRequest.builder().tableName(dynamoTableName).build()).toScala.asAsync
        }.void
      }
    }

  private def cloudwatchResource[F[_]: Async] = AWSResources.cloudWatch[F]()

  private class TestRecordProcessor extends ShardRecordProcessor {
    override def initialize(initializationInput: InitializationInput) = {
      initialized = true
      println(s"initialize for ${initializationInput.shardId()}")
    }
    override def processRecords(processRecordsInput: ProcessRecordsInput) = {
      fetchedRecords = fetchedRecords ++ processRecordsInput.records().asScala
      println("new events!")
      println(fetchedRecords)
    }
    override def leaseLost(leaseLostInput:   LeaseLostInput) = ()
    override def shardEnded(shardEndedInput: ShardEndedInput) = {
      println("shard input eded")
      shardEndedInput.checkpointer.checkpoint()
    }
    override def shutdownRequested(shutdownRequestedInput: ShutdownRequestedInput) = ()
  }

  private def scheduler[F[_]: Async] = (kinesisResource[F], dynamoDBResource[F], cloudwatchResource[F]).tupled.flatMap {
    case (kinesis, dynamo, cloudwatch) =>
      Resource.make[F, Scheduler] {
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

  private def consumerThread[F[_]: Async] = scheduler[F].flatMap { s =>
    Resource.make[F, Thread] {
      val schedulerThread = new Thread(s)
      schedulerThread.setDaemon(true)
      schedulerThread.start()
      10.tailRecM[F, Thread] { countdown: Int =>
        if (initialized) schedulerThread.asRight[Int].pure[F]
        else if (countdown >= 0 && !initialized)
          Async[F].defer {
            Thread.sleep(100)
            (countdown - 1).asLeft[Thread].pure[F]
          } else Async[F].raiseError(new Exception("Unable to initialize scheduler thread"))
      }
    } { schedulerThread =>
      Async[F].delay(schedulerThread.interrupt())
    }
  }

  override def busEnvironment[F[_]:  Async]: Resource[F, Unit] = consumerThread[F].void
  override def busConfigurator[F[_]: Sync]: Resource[F, EventBus.BusConfigurator[KinesisEnvelope]] =
    (
      AWSResources.kinesis[F](kinesisConfig),
      AWSResources.dynamo[F](dynamoConfig),
      AWSResources.cloudWatch[F]()
    ).mapN(KinesisBusConfigurator(KinesisBusConfig(appName, streamName, dynamoTableName), log))

  // test utilities

  override def isSafeForPublishing(msgSizes: Seq[Long]): Boolean =
    (msgSizes.length <= 500L) && msgSizes.forall(_ <= (1024L * 1024L)) && (msgSizes.sum <= (5L * 1024L * 1024L))
  override def busPublishDirectly[F[_]: Async](envelope: List[BusEnvelope]): F[Unit] = Async[F].defer {
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
  }
  override def busFetchNotProcessedDirectly[F[_]: Async](): F[List[BusEnvelope]] = Async[F].delay {
    fetchedRecords.map(r => KinesisEnvelope(r.partitionKey(), r.data(), None))
  }
  override def busMarkAllAsProcessed[F[_]: Async]: F[Unit] = Async[F].delay {
    fetchedRecords = List.empty
  }
}
