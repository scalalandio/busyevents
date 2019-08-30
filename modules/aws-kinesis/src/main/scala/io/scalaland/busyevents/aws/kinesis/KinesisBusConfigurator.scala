package io.scalaland.busyevents.aws.kinesis

import akka.stream.scaladsl._
import akka.{ Done, NotUsed }
import akka.actor.ActorSystem
import akka.stream.{ ActorMaterializer, SharedKillSwitch }
import cats.effect.{ Async, SyncIO, Timer }
import cats.implicits._
import com.typesafe.scalalogging.Logger
import io.scalaland.busyevents.utils.FutureToAsync
import io.scalaland.busyevents.EventBus.BusConfigurator
import px.kinesis.stream.consumer.Record
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.model.{ PutRecordsRequest, PutRecordsRequestEntry }

import scala.collection.JavaConverters._
import scala.compat.java8.FutureConverters._
import scala.concurrent.{ ExecutionContext, Future }

class KinesisBusConfigurator(
  kinesisBusConfig:    KinesisBusConfig,
  log:                 Logger,
  kinesisAsyncClient:  KinesisAsyncClient,
  busSchedulerFactory: KinesisBusSchedulerFactory
) extends BusConfigurator[KinesisEnvelope] { cfg =>

  import kinesisBusConfig._

  override def publishEvents[F[_]: Async: Timer](
    envelope:    List[KinesisEnvelope]
  )(implicit ec: ExecutionContext): F[Unit] =
    kinesisAsyncClient
      .putRecords(
        PutRecordsRequest
          .builder()
          .streamName(kinesisStreamName)
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

  override def unprocessedEvents(
    implicit system: ActorSystem,
    materializer:    ActorMaterializer,
    ec:              ExecutionContext
  ): (String, SharedKillSwitch) => Source[KinesisEnvelope, NotUsed] =
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
              busSchedulerFactory[SyncIO](workerId, killSwitch, publishSink).map(ec.execute).allocated.map {
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
        .map { record =>
          KinesisEnvelope(record.key, record.data.asByteBuffer, Option(record.markProcessed))
      }

  override def commitEventConsumed(
    implicit system: ActorSystem,
    materializer:    ActorMaterializer,
    ec:              ExecutionContext
  ): Sink[KinesisEnvelope, NotUsed] =
    Flow[KinesisEnvelope]
      .mapAsync(1) { envelope =>
        envelope.pure[Future].flatMap {
          case KinesisEnvelope(key, _, commit) =>
            commit.map(_()).getOrElse(Done.pure[Future]).map { d =>
              log.info(s"Event ${key} committed on Kinesis")
              d
            }
        }
      }
      .to(Sink.ignore)
}

object KinesisBusConfigurator {

  /** Useful if you wanted to make something like:
    *
    * {{{
    * (kinesisResource, dynamoResource, cloudWatchResource).mapN(KinesisBusConfigurator(config, log))
    * }}}
    *
    * uses "default" KinesisBusSchedulerFactory
    */
  def apply(kinesisBusConfig: KinesisBusConfig, log: Logger)(
    kinesisAsyncClient:       KinesisAsyncClient,
    dynamoDbAsyncClient:      DynamoDbAsyncClient,
    cloudWatchAsyncClient:    CloudWatchAsyncClient
  ): KinesisBusConfigurator =
    new KinesisBusConfigurator(
      kinesisBusConfig,
      log,
      kinesisAsyncClient,
      new KinesisBusSchedulerFactory(
        kinesisBusConfig,
        log,
        kinesisAsyncClient,
        dynamoDbAsyncClient,
        cloudWatchAsyncClient
      )
    )
}
