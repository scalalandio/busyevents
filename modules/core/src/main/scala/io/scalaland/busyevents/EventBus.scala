package io.scalaland.busyevents

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl._
import akka.stream.{ ActorMaterializer, SharedKillSwitch }
import cats.effect.{ Async, Timer }
import com.typesafe.scalalogging.Logger

import scala.concurrent.ExecutionContext

// TODO: support context as: Map[String, String] -> Ctx and extract it from headers od other metadata
// TODO: wrap logger with something configurable when it comes to msg format (e.g plain vs json)

class EventBus[Event, BusEnvelope, DLQEnvelope](
  config:                      StreamConfig,
  log:                         Logger,
  busConfigurator:             EventBus.BusConfigurator[BusEnvelope],
  deadLetterQueueConfigurator: EventBus.DeadLetterQueueConfigurator[DLQEnvelope]
) {

  import busConfigurator._
  import deadLetterQueueConfigurator._

  // TODO: describe guarantees that each implementation should (and shouldn't) provide

  // TODO: create an event scheduler, which would post events at specific moments in the future

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
}

object EventBus {

  // TODO: describe contracts that each configurator is expected to hold

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
    ): Flow[ConsumerError[Event], Unit, NotUsed]
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

  // TODO: docs
  def apply[Event, BusEnvelope, DLQEnvelope](config: StreamConfig, log: Logger)(
    busConfigurator:                                 EventBus.BusConfigurator[BusEnvelope],
    deadLetterQueueConfigurator:                     EventBus.DeadLetterQueueConfigurator[DLQEnvelope]
  ): EventBus[Event, BusEnvelope, DLQEnvelope] =
    new EventBus(config, log, busConfigurator, deadLetterQueueConfigurator)
}
