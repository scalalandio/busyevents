package io.scalaland.busyevents.aws.kinesis

import java.util.UUID

import akka.NotUsed
import akka.actor.{ ActorRef, ActorSystem }
import akka.event.LoggingAdapter
import akka.stream.{ ActorMaterializer, SharedKillSwitch }
import akka.stream.scaladsl.Sink
import cats.effect.{ Resource, Sync, Timer }
import cats.implicits._
import com.typesafe.scalalogging.Logger
import px.kinesis.stream.consumer.{ Record, RecordProcessorFactoryImpl }
import px.kinesis.stream.consumer.checkpoint.{ CheckpointConfig, CheckpointTracker, CheckpointTrackerActor }
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.kinesis.common.{ ConfigsBuilder, InitialPositionInStreamExtended }
import software.amazon.kinesis.coordinator.{ Scheduler, SchedulerCoordinatorFactory }
import software.amazon.kinesis.retrieval.polling.PollingConfig
import software.amazon.kinesis.retrieval.RetrievalConfig

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class KinesisBusSchedulerFactory(kinesisBusConfig:      KinesisBusConfig,
                                 log:                   Logger,
                                 kinesisAsyncClient:    KinesisAsyncClient,
                                 dynamoDbAsyncClient:   DynamoDbAsyncClient,
                                 cloudWatchAsyncClient: CloudWatchAsyncClient,
                                 checkpointConfig:      CheckpointConfig = CheckpointConfig(),
                                 coordinatorFactory:    SchedulerCoordinatorFactory = new SchedulerCoordinatorFactory) {
  cfg =>

  import kinesisBusConfig._

  def apply[F[_]: Sync: Timer](workerId: String, killSwitch: SharedKillSwitch, publishSink: Sink[Record, NotUsed])(
    implicit system: ActorSystem,
    materializer:    ActorMaterializer,
    ec:              ExecutionContext
  ): Resource[F, Scheduler] = Resource[F, Scheduler](
    Sync[F]
      .delay {
        val uniqueWorkerID = s"$workerId-${UUID.randomUUID()}"

        val schedulerConfig =
          new ConfigsBuilder(
            kinesisStreamName,
            appName,
            kinesisAsyncClient,
            dynamoDbAsyncClient,
            cloudWatchAsyncClient,
            uniqueWorkerID,
            recordProcessorFactory(uniqueWorkerID, killSwitch, publishSink)
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
      .fproduct(
        scheduler =>
          Sync[F].delay(scheduler.startGracefulShutdown()).flatMap { f =>
            60.tailRecM[F, Unit] { attempts =>
              if (f.isDone) ().asRight[Int].pure[F]
              else if (f.isCancelled) new Exception("Scheduler shutdown cancelled").raiseError[F, Either[Int, Unit]]
              else if (attempts < 0) new Exception("Scheduler shutdown timed out").raiseError[F, Either[Int, Unit]]
              else Timer[F].sleep(1.second) *> (attempts - 1).asLeft[Unit].pure[F]
            }
        }
      )
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
    new RecordProcessorFactoryImpl(
      publishSink,
      workerId,
      new CheckpointTracker(
        UUID.randomUUID().toString, // prevents "actor name [...] is not unique!" (see below)
        checkpointConfig.maxBufferSize,
        checkpointConfig.maxDurationInSeconds,
        checkpointConfig.completionTimeout,
        checkpointConfig.timeout
      ) {

        override val tracker: ActorRef = {
          system.actorOf(
            CheckpointTrackerActor.props(workerId,
                                         checkpointConfig.maxBufferSize,
                                         checkpointConfig.maxDurationInSeconds),
            s"tracker-$workerId" // name = s"tracker-${workerId.take(5)}" causes A LOT of issues
          )
        }
      },
      killSwitch
    )
}
