package io.scalaland.busyevents

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl._
import akka.stream.{ ActorMaterializer, SharedKillSwitch }
import cats.effect.{ Async, Timer }
import com.typesafe.scalalogging.Logger

import scala.concurrent.ExecutionContext

class EventBus[Event, BusEnvelope, DLQEnvelope](
  config:                      StreamConfig,
  log:                         Logger,
  busConfigurator:             EventBus.BusConfigurator[BusEnvelope],
  deadLetterQueueConfigurator: EventBus.DeadLetterQueueConfigurator[DLQEnvelope]
) {

  import busConfigurator._
  import deadLetterQueueConfigurator._

  def publisher[F[_]: Async: Timer](implicit eventEncoder: EventEncoder[Event],
                                    enveloper: Enveloper[BusEnvelope],
                                    ec:        ExecutionContext): Publisher[F, BusEnvelope, Event] =
    new Publisher[F, BusEnvelope, Event](publishEvents[F])

  def consumer(
    implicit system:   ActorSystem,
    materializer:      ActorMaterializer,
    executionContext:  ExecutionContext,
    eventDecoder:      EventDecoder[Event],
    eventEncoder:      EventEncoder[Event],
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
    materializer:      ActorMaterializer,
    executionContext:  ExecutionContext,
    eventDecoder:      EventDecoder[Event],
    eventEncoder:      EventEncoder[Event],
    envelopeExtractor: Extractor[DLQEnvelope]
  ): EventRepairer[DLQEnvelope, Event] = new EventRepairer[DLQEnvelope, Event](
    config,
    log,
    deadLetterEvents,
    deadLetterEnqueue,
    deadLetterDequeue
  )

  // TODO: just an idea for now
  def migrateTo[NewBusEnvelope, NewDLQEnvelope](eventBus: EventBus[Event, NewBusEnvelope, NewDLQEnvelope]): Unit =
    migrateAdjustedTo(eventBus)(identity[Event])
  def migrateAdjustedTo[NewEvent, NewBusEnvelope, NewDLQEnvelope](
    eventBus: EventBus[NewEvent, NewBusEnvelope, NewDLQEnvelope]
  )(
    migration: Event => NewEvent
  ): Unit = {
    eventBus.hashCode()
    migration.hashCode()
    ()
  }
}

object EventBus {

  trait BusConfigurator[BusEnvelope] {

    def publishEvents[F[_]: Async: Timer](envelope: List[BusEnvelope])(implicit ec: ExecutionContext): F[Unit]

    def unprocessedEvents(
      implicit system: ActorSystem,
      materializer:    ActorMaterializer,
      ec:              ExecutionContext
    ): (String, SharedKillSwitch) => Source[BusEnvelope, NotUsed]
    def commitEventConsumed(
      implicit system: ActorSystem,
      materializer:    ActorMaterializer,
      ec:              ExecutionContext
    ): Sink[BusEnvelope, NotUsed]
  }

  trait DeadLetterQueueConfigurator[DLQEnvelope] {

    def deadLetterEnqueue[Event: EventEncoder](
      implicit system: ActorSystem,
      materializer:    ActorMaterializer,
      ec:              ExecutionContext
    ): Flow[EventError[Event], Unit, NotUsed]
    def deadLetterEvents(
      implicit system: ActorSystem,
      materializer:    ActorMaterializer,
      ec:              ExecutionContext
    ): (String, SharedKillSwitch) => Source[DLQEnvelope, NotUsed]
    def deadLetterDequeue(
      implicit system: ActorSystem,
      materializer:    ActorMaterializer,
      ec:              ExecutionContext
    ): Sink[DLQEnvelope, NotUsed]
  }

  def apply[Event, BusEnvelope, DLQEnvelope](
    config:                      StreamConfig,
    log:                         Logger,
    busConfigurator:             EventBus.BusConfigurator[BusEnvelope],
    deadLetterQueueConfigurator: EventBus.DeadLetterQueueConfigurator[DLQEnvelope]
  ): EventBus[Event, BusEnvelope, DLQEnvelope] =
    new EventBus(config, log, busConfigurator, deadLetterQueueConfigurator)
}
