package io.scalaland.busyevents

import akka.{ Done, NotUsed }
import akka.actor.ActorSystem
import akka.stream.{ KillSwitch, KillSwitches, Materializer }
import akka.stream.scaladsl._
import cats.{ Eval, MonadError }
import cats.implicits._
import com.typesafe.scalalogging.Logger
import monix.eval.Task
import monix.execution.Scheduler

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

sealed abstract class Processor[Envelope: Extractor, Event: EventDecoder](config: StreamConfig,
                                                                          protected val log: Logger)(
  implicit system:                                                                           ActorSystem
) {

  import config._

  protected final val killSwitch = KillSwitches.shared(workerId)

  protected final val eventExtraction: Flow[Envelope, RawEvent, NotUsed] = Flow[Envelope].map(Extractor[Envelope].apply)

  protected final val eventDecoding: Flow[RawEvent, EventDecodingResult[Event], NotUsed] =
    Flow[RawEvent].map(EventDecoder[Event].apply)

  protected def eventProcessing[F[_]: MonadError[?, Throwable]: RunToFuture](
    processor: PartialFunction[Event, F[Unit]]
  ): Flow[Event, Either[EventError[Envelope, Event], Unit], NotUsed] =
    Flow[Event].mapAsync(processorParallelism) { event: Event =>
      RunToFuture[F].apply[Either[EventError[Envelope, Event], Unit]](
        processor
          .andThen { result =>
            result.attempt.map {
              case Left(error) => EventError.ProcessingError(error.getMessage, event, Some(error))
              case right       => right
            }
          }
          .applyOrElse[Event, F[Either[EventError[Envelope, Event], Unit]]](
            event,
            _ => ().asRight[EventError[Envelope, Event]].pure[F]
          )
      )
    }

  protected def streamWithRetry[T](thunk: => Future[T]): Eval[(Future[T], KillSwitch)] = {

    case class Retry(attempt: Int = 0, delay: FiniteDuration = minBackoff) {
      def shouldRetry: Boolean = attempt < maxRetries
      def nextRetry: Retry =
        copy(attempt + 1, (delay * randomFactor).asInstanceOf[FiniteDuration] min maxBackoff)
    }

    Eval.later(killSwitch).map[(Future[T], KillSwitch)] { killSwitch =>
      // TODO: rethink how to NOT rely on Monix implementation
      implicit val scheduler: Scheduler = Scheduler(system.dispatcher)

      Task
        .deferFuture[T] { thunk }
        .onErrorRestartLoop(Retry()) { (error, retry, makeRetry) =>
          if (retry.shouldRetry) {
            log.error(s"Event stream returned error, restarting due to retry policy", error)
            makeRetry(retry.nextRetry).delayResult(retry.delay)
          } else {
            log.error(s"Event stream returned error, terminating due to retry policy", error)
            Task.raiseError(error)
          }
        }
        .runToFuture -> killSwitch
    }
  }
}

final class Consumer[Envelope: Extractor, Event: EventDecoder](
  config:              StreamConfig,
  log:                 Logger,
  unprocessedEvents:   Source[Envelope, Future[Done]],
  deadLetterEnqueue:   Flow[EventError[Envelope, Event], Unit, NotUsed],
  commitEventConsumed: Sink[Envelope, NotUsed]
)(
  implicit system: ActorSystem,
  materializer:    Materializer
) extends Processor[Envelope, Event](config, log) {

  def start[F[_]: MonadError[?, Throwable]: RunToFuture](
    processor: PartialFunction[Event, F[Unit]]
  ): Eval[(Future[Done], KillSwitch)] = {
    val processing = eventProcessing[F](processor)
    val pipe = unprocessedEvents
      .map(e => e -> e)
      .via(eventExtraction.withContext[Envelope])
      .via(eventDecoding.withContext[Envelope])
      .via(
        Flow[(EventDecodingResult[Event], Envelope)].flatMapConcat {
          case (EventDecodingResult.Success(event), envelope) =>
            Source.single[Event](event).via(processing).map(_ -> envelope)
          case (EventDecodingResult.Failure(message), envelope) =>
            Source.single(Left(EventError.DecodingError(message, envelope)) -> envelope)
          case (EventDecodingResult.Skipped, envelope) => Source.single(Right(()) -> envelope)
        }
      )
      .via(
        Flow[Either[EventError[Envelope, Event], Unit]]
          .flatMapConcat {
            case Right(_)    => Source.single(())
            case Left(error) => Source.single(error).via(deadLetterEnqueue)
          }
          .withContext[Envelope]
      )
      .map(_._2)
      .to(commitEventConsumed)

    streamWithRetry(pipe.run())
  }
}

final class EventRepairer[Envelope: Extractor, Event: EventDecoder](
  config:              StreamConfig,
  log:                 Logger,
  deadLetterEvents:    Source[Envelope, Future[Done]],
  requeueFailedEvents: Flow[EventError[Envelope, Event], Unit, NotUsed],
  deadLetterDequeue:   Sink[Envelope, NotUsed]
)(
  implicit system: ActorSystem,
  materializer:    Materializer
) extends Processor[Envelope, Event](config, log) {

  def start[F[_]: MonadError[?, Throwable]: RunToFuture](
    processor: PartialFunction[Event, F[Unit]]
  ): Eval[(Future[Done], KillSwitch)] = {
    val processing = eventProcessing[F](processor)
    val pipe = deadLetterEvents
      .map(envelope => envelope -> envelope)
      .via(eventExtraction.withContext[Envelope])
      .via(eventDecoding.withContext[Envelope])
      .via(
        Flow[(EventDecodingResult[Event], Envelope)].flatMapConcat {
          case (EventDecodingResult.Success(event), envelope) =>
            Source.single[Event](event).via(processing).map(_ -> envelope)
          case (EventDecodingResult.Failure(message), envelope) =>
            Source.single(Left(EventError.DecodingError(message, envelope)) -> envelope)
          case (EventDecodingResult.Skipped, envelope) => Source.single(Right(()) -> envelope)
        }
      )
      .via(
        Flow[Either[EventError[Envelope, Event], Unit]]
          .flatMapConcat {
            case Right(_)    => Source.single(())
            case Left(error) => Source.single(error).via(requeueFailedEvents)
          }
          .withContext[Envelope]
      )
      .map(_._2)
      .to(deadLetterDequeue)

    streamWithRetry(pipe.run())
  }
}
