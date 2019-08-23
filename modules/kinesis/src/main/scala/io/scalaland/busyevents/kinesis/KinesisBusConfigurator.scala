package io.scalaland.busyevents
package kinesis

import akka.stream.scaladsl._
import akka.{ Done, NotUsed }
import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.stream.{ ActorMaterializer, SharedKillSwitch }
import cats.effect.{ Async, Resource, Sync, SyncIO }
import cats.implicits._
import com.typesafe.scalalogging.Logger
import px.kinesis.stream.consumer.{ Record, RecordProcessorFactoryImpl }
import px.kinesis.stream.consumer
import px.kinesis.stream.consumer.checkpoint.{ CheckpointConfig, CheckpointTracker }
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.kinesis.common.{ ConfigsBuilder, InitialPositionInStreamExtended }
import software.amazon.kinesis.coordinator.{ Scheduler, SchedulerCoordinatorFactory }
import software.amazon.kinesis.retrieval.polling.PollingConfig
import software.amazon.kinesis.retrieval.RetrievalConfig

import scala.concurrent.ExecutionContext

class KinesisBusConfigurator(
  kinesisBusConfig:      KinesisBusConfig,
  log:                   Logger,
  kinesisAsyncClient:    KinesisAsyncClient,
  dynamoDbAsyncClient:   DynamoDbAsyncClient,
  cloudWatchAsyncClient: CloudWatchAsyncClient
) extends EventBus.BusConfigurator[Record] { cfg =>

  import kinesisBusConfig._

  override def publishEvents[F[_]: Async](envelope: List[Record]): F[Unit] = ???

  override def unprocessedEvents(
    implicit system: ActorSystem,
    materializer:    ActorMaterializer,
    ec:              ExecutionContext
  ): (String, SharedKillSwitch) => Source[Record, NotUsed] =
    (workerId, killSwitch) =>
      // This is virtually a copy paste of
      //   import px.kinesis.stream.consumer
      //   consumer.source(setting)
      // which uses our own settings and our own way of initializing Scheduler.
      MergeHub
        .source[Record](perProducerBufferSize = 1)
        .via(killSwitch.flow)
        .watchTermination()(Keep.both)
        .mapMaterializedValue {
          case (publishSink, terminationFuture) =>
            // custom Scheduler is a reason we haven't used px.kinesis.stream.consumer.source(_)
            (SyncIO(log.info("Creating new Scheduler")) *>
              createScheduler[SyncIO](workerId, killSwitch, publishSink).map(ec.execute).allocated.map {
                case (_, shutdown) =>
                  terminationFuture
                    .map { _ =>
                      log.info("Shutting down Scheduler due to stream completion")
                      shutdown.unsafeRunSync()
                      Done
                    }
                    .recover {
                      case ex: Throwable =>
                        log.error("Shutting down Scheduler due to failure", ex)
                        shutdown.unsafeRunSync()
                        Done
                    }
              }).unsafeRunSync()
        }
        .mapMaterializedValue(_ => NotUsed.getInstance)

  override def commitEventConsumed(
    implicit system: ActorSystem,
    materializer:    ActorMaterializer,
    ec:              ExecutionContext
  ): Sink[Record, NotUsed] =
    consumer
      .commitFlow(1)
      .map { record =>
        log.info(s"Event ${record.key} committed on Kinesis")
      }
      .to(Sink.ignore)

  // protected in case someone wanted to tune them a bit

  protected def coordinatorFactory: SchedulerCoordinatorFactory = new SchedulerCoordinatorFactory

  protected def recordProcessorFactory(workerId:    String,
                                       killSwitch:  SharedKillSwitch,
                                       publishSink: Sink[Record, NotUsed])(
    implicit system:                                ActorSystem,
    materializer:                                   ActorMaterializer,
    ec:                                             ExecutionContext
  ): RecordProcessorFactoryImpl = {
    // TODO: pass settings
    val tracker = CheckpointTracker(workerId, CheckpointConfig())
    implicit val logger: LoggingAdapter = new LoggingAdapter {
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
    new RecordProcessorFactoryImpl(publishSink, workerId, tracker, killSwitch)
  }

  protected def createScheduler[F[_]: Sync](workerId: String,
                                            killSwitch:  SharedKillSwitch,
                                            publishSink: Sink[Record, NotUsed])(
    implicit system:                                     ActorSystem,
    materializer:                                        ActorMaterializer,
    ec:                                                  ExecutionContext
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
}

object KinesisBusConfigurator {

  /** Useful if you wanted to make something like:
   *
   * {{{
   * (kinesisResource, dynamoResource, cloudWatchResource).mapN(KinesisBusConfigurator(config, log))
   * }}}
   */
  def apply(kinesisBusConfig: KinesisBusConfig, log: Logger)(
    kinesisAsyncClient:       KinesisAsyncClient,
    dynamoDbAsyncClient:      DynamoDbAsyncClient,
    cloudWatchAsyncClient:    CloudWatchAsyncClient
  ): KinesisBusConfigurator =
    new KinesisBusConfigurator(kinesisBusConfig, log, kinesisAsyncClient, dynamoDbAsyncClient, cloudWatchAsyncClient)
}
