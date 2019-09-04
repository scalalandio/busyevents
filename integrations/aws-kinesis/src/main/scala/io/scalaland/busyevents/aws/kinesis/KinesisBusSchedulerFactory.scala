package io.scalaland.busyevents.aws.kinesis

import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import akka.NotUsed
import akka.event.LoggingAdapter
import akka.stream.{ ActorMaterializer, SharedKillSwitch }
import cats.effect.{ Resource, Sync }
import cats.implicits._
import com.typesafe.scalalogging.Logger
import px.kinesis.stream.consumer.{ Record, RecordProcessorFactoryImpl }
import px.kinesis.stream.consumer.checkpoint.{ CheckpointConfig, CheckpointTracker }
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.kinesis.common.{ ConfigsBuilder, InitialPositionInStreamExtended }
import software.amazon.kinesis.coordinator.{ Scheduler, SchedulerCoordinatorFactory }
import software.amazon.kinesis.retrieval.polling.PollingConfig
import software.amazon.kinesis.retrieval.RetrievalConfig

import scala.concurrent.ExecutionContext

class KinesisBusSchedulerFactory(kinesisBusConfig:      KinesisBusConfig,
                                 log:                   Logger,
                                 kinesisAsyncClient:    KinesisAsyncClient,
                                 dynamoDbAsyncClient:   DynamoDbAsyncClient,
                                 cloudWatchAsyncClient: CloudWatchAsyncClient,
                                 checkpointConfig:      CheckpointConfig = CheckpointConfig(),
                                 coordinatorFactory:    SchedulerCoordinatorFactory = new SchedulerCoordinatorFactory) {
  cfg =>

  import kinesisBusConfig._

  def apply[F[_]: Sync](workerId: String, killSwitch: SharedKillSwitch, publishSink: Sink[Record, NotUsed])(
    implicit system: ActorSystem,
    materializer:    ActorMaterializer,
    ec:              ExecutionContext
  ): Resource[F, Scheduler] = Resource[F, Scheduler](
    Sync[F]
      .delay {
        val schedulerConfig =
          new ConfigsBuilder(
            kinesisStreamName,
            appName,
            kinesisAsyncClient,
            dynamoDbAsyncClient,
            cloudWatchAsyncClient,
            workerId,
            recordProcessorFactory(workerId, killSwitch, publishSink)
          ).tableName(dynamoTableName)

        val pollingConfig: PollingConfig =
          new PollingConfig(kinesisStreamName, kinesisAsyncClient)
        val retrievalConfig: RetrievalConfig =
          new RetrievalConfig(kinesisAsyncClient, kinesisStreamName, appName)
            .initialPositionInStreamExtended(
              InitialPositionInStreamExtended.newInitialPosition(initialPositionInStream)
            )
            .retrievalSpecificConfig(pollingConfig)

        new Scheduler(
          schedulerConfig.checkpointConfig,
          schedulerConfig.coordinatorConfig
            .parentShardPollIntervalMillis(parentShardPollIntervalMillis)
            .coordinatorFactory(coordinatorFactory),
          schedulerConfig.leaseManagementConfig,
          schedulerConfig.lifecycleConfig,
          schedulerConfig.metricsConfig.metricsLevel(metricsLevel),
          schedulerConfig.processorConfig,
          retrievalConfig
        )
      }
      .fproduct(scheduler => Sync[F].delay(scheduler.shutdown()))
  )

  // overridable

  protected implicit def logger: LoggingAdapter = new LoggingAdapter {
    def isErrorEnabled:   Boolean = true
    def isWarningEnabled: Boolean = true
    def isInfoEnabled:    Boolean = true
    def isDebugEnabled:   Boolean = true

    protected def notifyError(message:   String): Unit = cfg.log.error(message)
    protected def notifyError(cause:     Throwable, message: String): Unit = cfg.log.error(message, cause)
    protected def notifyWarning(message: String): Unit = cfg.log.warn(message)
    protected def notifyInfo(message:    String): Unit = cfg.log.info(message)
    protected def notifyDebug(message:   String): Unit = cfg.log.debug(message)
  }

  protected def recordProcessorFactory(workerId:    String,
                                       killSwitch:  SharedKillSwitch,
                                       publishSink: Sink[Record, NotUsed])(
    implicit system:                                ActorSystem,
    materializer:                                   ActorMaterializer,
    ec:                                             ExecutionContext
  ): RecordProcessorFactoryImpl =
    new RecordProcessorFactoryImpl(publishSink, workerId, CheckpointTracker(workerId, checkpointConfig), killSwitch)
}
