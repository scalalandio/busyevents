package io.scalaland.busyevents

import akka.{ Done, NotUsed }
import akka.actor.ActorSystem
import akka.stream.{ KillSwitch, KillSwitches, Materializer, SharedKillSwitch }
import akka.stream.scaladsl._
import cats.{ Eval, MonadError }
import cats.implicits._
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.FiniteDuration

sealed abstract class Processor[Envelope: Extractor, Event: EventDecoder](config: StreamConfig,
                                                                          protected val log: Logger)(
  implicit system:                                                                           ActorSystem
) {

  import config._

  protected final val killSwitch: SharedKillSwitch = KillSwitches.shared(workerId)

  protected final val eventExtraction: Flow[Envelope, RawEvent, NotUsed] = Flow[Envelope].map(Extractor[Envelope].apply)

  protected final val eventDecoding: Flow[RawEvent, EventDecodingResult[Event], NotUsed] =
    Flow[RawEvent].map(EventDecoder[Event].apply)

  protected def eventProcessing[F[_]: MonadError[?[_], Throwable]: RunToFuture](
    processor: PartialFunction[Event, F[Unit]]
  ): Flow[Event, Either[EventError[Event], Unit], NotUsed] =
    Flow[Event].mapAsync(processorParallelism) { event: Event =>
      RunToFuture[F].apply[Either[EventError[Event], Unit]](
        processor
          .andThen(_.attempt.map {
            _.leftMap(error => EventError.ProcessingError(error.getMessage, event, Some(error)): EventError[Event])
          })
          .applyOrElse[Event, F[Either[EventError[Event], Unit]]](
            event,
            _ => ().asRight[EventError[Event]].pure[F]
          )
      )
    }

  protected def streamWithRetry[T](thunk: => Future[T]): Eval[(Future[T], KillSwitch)] = {

    case class Retry(attempt: Int = 0, delay: FiniteDuration = minBackoff) {
      def shouldRetry: Boolean = attempt < maxRetries
      def nextRetry: Retry =
        copy(attempt + 1, ((delay * randomFactor) match {
          case fd: FiniteDuration => fd
          case _ => maxBackoff
        }) min maxBackoff)
    }

    Eval.later(killSwitch).map[(Future[T], KillSwitch)] { killSwitch =>
      implicit val ec: ExecutionContext = system.dispatcher

      val future = Retry().tailRecM[Future, T] { retry =>
        thunk.map(_.asRight[Retry]).recoverWith {
          case error if retry.shouldRetry =>
            akka.pattern.after(retry.delay, system.scheduler)(
              Future.successful {
                log.error(s"Event stream returned error, restarting due to retry policy", error)
                retry.nextRetry.asLeft[T]
              }
            )
          case error =>
            Future.failed {
              log.error(s"Event stream returned error, terminating due to retry policy", error)
              error
            }
        }
      }

      future -> killSwitch
    }
  }
}

final class Consumer[BusEnvelope: Extractor, Event: EventDecoder](
  config:              StreamConfig,
  log:                 Logger,
  unprocessedEvents:   Source[BusEnvelope, Future[Done]],
  deadLetterEnqueue:   Flow[EventError[Event], Unit, NotUsed],
  commitEventConsumed: Sink[BusEnvelope, NotUsed]
)(
  implicit system: ActorSystem,
  materializer:    Materializer
) extends Processor[BusEnvelope, Event](config, log) {

  def start[F[_]: MonadError[?[_], Throwable]: RunToFuture](
    processor: PartialFunction[Event, F[Unit]]
  ): Eval[(Future[Done], KillSwitch)] = {
    val processing = eventProcessing[F](processor)
    val pipe = unprocessedEvents
      .map(e => e -> e)
      .via(eventExtraction.withContext[BusEnvelope])
      .via(eventDecoding.withContext[BusEnvelope])
      .via(
        Flow[(EventDecodingResult[Event], BusEnvelope)].flatMapConcat {
          case (EventDecodingResult.Success(event), envelope) =>
            Source.single[Event](event).via(processing).map(_ -> envelope)
          case (EventDecodingResult.Failure(message), envelope) =>
            Source.single(Left(EventError.DecodingError(message, envelope)) -> envelope)
          case (EventDecodingResult.Skipped, envelope) => Source.single(Right(()) -> envelope)
        }
      )
      .via(
        Flow[Either[EventError[Event], Unit]]
          .flatMapConcat {
            case Right(_)    => Source.single(())
            case Left(error) => Source.single(error).via(deadLetterEnqueue)
          }
          .withContext[BusEnvelope]
      )
      .map(_._2)
      .to(commitEventConsumed)

    streamWithRetry(pipe.run())
  }
}

final class EventRepairer[DLQEnvelope: Extractor, Event: EventDecoder](
  config:              StreamConfig,
  log:                 Logger,
  deadLetterEvents:    Source[DLQEnvelope, Future[Done]],
  requeueFailedEvents: Flow[EventError[Event], Unit, NotUsed],
  deadLetterDequeue:   Sink[DLQEnvelope, NotUsed]
)(
  implicit system: ActorSystem,
  materializer:    Materializer
) extends Processor[DLQEnvelope, Event](config, log) {

  def start[F[_]: MonadError[?[_], Throwable]: RunToFuture](
    processor: PartialFunction[Event, F[Unit]]
  ): Eval[(Future[Done], KillSwitch)] = {
    val processing = eventProcessing[F](processor)
    val pipe = deadLetterEvents
      .map(envelope => envelope -> envelope)
      .via(eventExtraction.withContext[DLQEnvelope])
      .via(eventDecoding.withContext[DLQEnvelope])
      .via(
        Flow[(EventDecodingResult[Event], DLQEnvelope)].flatMapConcat {
          case (EventDecodingResult.Success(event), envelope) =>
            Source.single[Event](event).via(processing).map(_ -> envelope)
          case (EventDecodingResult.Failure(message), envelope) =>
            Source.single(Left(EventError.DecodingError(message, Extractor[DLQEnvelope].apply(envelope))) -> envelope)
          case (EventDecodingResult.Skipped, envelope) => Source.single(Right(()) -> envelope)
        }
      )
      .via(
        Flow[Either[EventError[Event], Unit]]
          .flatMapConcat {
            case Right(_)    => Source.single(())
            case Left(error) => Source.single(error).via(requeueFailedEvents)
          }
          .withContext[DLQEnvelope]
      )
      .map(_._2)
      .to(deadLetterDequeue)

    streamWithRetry(pipe.run())
  }
}
