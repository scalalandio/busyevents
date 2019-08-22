package io.scalaland.busyevents

import akka.{ Done, NotUsed }
import akka.actor.ActorSystem
import akka.stream.scaladsl._
import akka.stream.Materializer
import cats.effect.Async
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

class EventBus[Event, BusEnvelope, DLQEnvelope](
  config:                      StreamConfig,
  log:                         Logger,
  busConfigurator:             EventBus.BusConfigurator[BusEnvelope],
  deadLetterQueueConfigurator: EventBus.DeadLetterQueueConfigurator[DLQEnvelope, Event]
) {

  import busConfigurator._
  import deadLetterQueueConfigurator._

  def publisher[F[_]: Async](implicit eventEncoder: EventEncoder[Event],
                             enveloper: Enveloper[BusEnvelope]): Publisher[F, BusEnvelope, Event] =
    new Publisher[F, BusEnvelope, Event](publishEvents[F])

  def consumer(
    implicit system:   ActorSystem,
    materializer:      Materializer,
    eventDecoder:      EventDecoder[Event],
    envelopeExtractor: Extractor[BusEnvelope]
  ): Consumer[BusEnvelope, Event] = new Consumer(
    config,
    log,
    unprocessedEvents,
    deadLetterEnqueue,
    commitEventConsumed
  )

  def repairer(
    implicit system:   ActorSystem,
    materializer:      Materializer,
    eventDecoder:      EventDecoder[Event],
    envelopeExtractor: Extractor[DLQEnvelope]
  ): EventRepairer[DLQEnvelope, Event] = new EventRepairer[DLQEnvelope, Event](
    config,
    log,
    deadLetterEvents,
    requeueFailedEvents,
    deadLetterDequeue
  )
}

object EventBus {

  trait BusConfigurator[BusEnvelope] {

    def publishEvents[F[_]: Async](envelope: List[BusEnvelope]): F[Unit]

    def unprocessedEvents:   Source[BusEnvelope, Future[Done]]
    def commitEventConsumed: Sink[BusEnvelope, NotUsed]
  }

  trait DeadLetterQueueConfigurator[DLQEnvelope, Event] {

    def deadLetterEnqueue:   Flow[EventError[Event], Unit, NotUsed]
    def deadLetterEvents:    Source[DLQEnvelope, Future[Done]]
    def requeueFailedEvents: Flow[EventError[Event], Unit, NotUsed]
    def deadLetterDequeue:   Sink[DLQEnvelope, NotUsed]
  }

  def apply[Event, BusEnvelope, DLQEnvelope](
    config:                      StreamConfig,
    log:                         Logger,
    busConfigurator:             EventBus.BusConfigurator[BusEnvelope],
    deadLetterQueueConfigurator: EventBus.DeadLetterQueueConfigurator[DLQEnvelope, Event]
  ): EventBus[Event, BusEnvelope, DLQEnvelope] =
    new EventBus(config, log, busConfigurator, deadLetterQueueConfigurator)
}
