package io.scalaland.busyevents

import akka.{ Done, NotUsed }
import akka.actor.ActorSystem
import akka.stream.{ KillSwitch, KillSwitches, Materializer }
import akka.stream.scaladsl._
import cats.Eval
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

  protected def eventProcessing[F[_]: RunToFuture](
    processor: PartialFunction[Event, F[Unit]]
  ): Flow[Event, Either[EventError[Envelope, Event], Unit], NotUsed] = ???

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

sealed abstract class Consumer[Envelope: Extractor, Event: EventDecoder](
  config: StreamConfig,
  log:    Logger
)(
  implicit system: ActorSystem,
  materializer:    Materializer
) extends Processor[Envelope, Event](config, log) {

  protected val unprocessedEvents: Source[Envelope, Future[Done]]

  protected val deadLetterQueue: Flow[EventError[Envelope, Event], Unit, NotUsed]

  protected val commitEvent: Sink[Envelope, NotUsed]

  final def start[F[_]: RunToFuture](processor: PartialFunction[Event, F[Unit]]): Eval[(Future[Done], KillSwitch)] = {
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
            case Left(error) => Source.single(error).via(deadLetterQueue)
          }
          .withContext[Envelope]
      )
      .map(_._2)
      .to(commitEvent)

    streamWithRetry(pipe.run())
  }
}
