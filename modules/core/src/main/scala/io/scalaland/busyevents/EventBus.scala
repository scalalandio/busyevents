package io.scalaland.busyevents

import akka.{ Done, NotUsed }
import akka.actor.ActorSystem
import akka.stream.scaladsl._
import akka.stream.Materializer
import cats.effect.Async
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

class EventBus[Event, BusEnvelope, DeadLetterQueueEnvelope](
  config:                      StreamConfig,
  log:                         Logger,
  busConfigurator:             EventBus.BusConfigurator[BusEnvelope],
  deadLetterQueueConfigurator: EventBus.DeadLetterQueueConfigurator[DeadLetterQueueEnvelope, Event]
) {

  import busConfigurator._
  import deadLetterQueueConfigurator._

  def publisher[F[_]: Async]: Publisher[F, BusEnvelope] = new Publisher[F, BusEnvelope](e => publishEvents[F](e))

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
    envelopeExtractor: Extractor[DeadLetterQueueEnvelope]
  ): EventRepairer[DeadLetterQueueEnvelope, Event] = new EventRepairer[DeadLetterQueueEnvelope, Event](
    config,
    log,
    deadLetterEvents,
    requeueFailedEvents,
    deadLetterDequeue
  )
}

object EventBus {

  trait BusConfigurator[Envelope] {

    def publishEvents[F[_]: Async](envelope: List[Envelope]): F[Unit]

    def unprocessedEvents:   Source[Envelope, Future[Done]]
    def commitEventConsumed: Sink[Envelope, NotUsed]
  }

  trait DeadLetterQueueConfigurator[Envelope, Event] {

    def deadLetterEnqueue:   Flow[EventError[Envelope, Event], Unit, NotUsed]
    def deadLetterEvents:    Source[Envelope, Future[Done]]
    def requeueFailedEvents: Flow[EventError[Envelope, Event], Unit, NotUsed]
    def deadLetterDequeue:   Sink[Envelope, NotUsed]
  }

  def apply[Event, BusEnvelope, DeadLetterQueueEnvelope](
    config:                      StreamConfig,
    log:                         Logger,
    busConfigurator:             EventBus.BusConfigurator[BusEnvelope],
    deadLetterQueueConfigurator: EventBus.DeadLetterQueueConfigurator[DeadLetterQueueEnvelope, Event]
  ): EventBus[Event, BusEnvelope, DeadLetterQueueEnvelope] =
    new EventBus(config, log, busConfigurator, deadLetterQueueConfigurator)
}
