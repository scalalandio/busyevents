package io.scalaland.busyevents

import akka.actor.ActorSystem
import akka.stream.{ ActorMaterializer, KillSwitch }
import akka.Done
import cats.effect.{ Async, Timer }
import cats.Eval
import cats.implicits._

import scala.concurrent.{ ExecutionContext, Future }

final class Migration[OldEvent:       EventDecoder: EventEncoder,
                      OldBusEnvelope: Extractor,
                      OldDLQEnvelope,
                      NewEvent:       EventEncoder,
                      NewBusEnvelope: Enveloper,
                      NewDLQEnvelope](
  oldEventBus:     EventBus[OldEvent, OldBusEnvelope, OldDLQEnvelope],
  newEventBus:     EventBus[NewEvent, NewBusEnvelope, NewDLQEnvelope]
)(implicit system: ActorSystem, materializer: ActorMaterializer, executionContext: ExecutionContext) {

  def migrateWith[F[_]: Async: Timer: RunToFuture](
    eventMigration: OldEvent => F[NewEvent]
  ): Eval[(Future[Done], KillSwitch)] = {
    val publisher = newEventBus.publisher[F]
    oldEventBus.consumer.start[F] {
      case oldEvent => eventMigration(oldEvent).flatMap(publisher.publishEvent)
    }
  }

  def migrate[F[_]: Async: Timer: RunToFuture](
    implicit ev: OldEvent =:= NewEvent
  ): Eval[(Future[Done], KillSwitch)] = {
    val publisher = newEventBus.publisher[F]
    oldEventBus.consumer.start[F] {
      case oldEvent => publisher.publishEvent(oldEvent)
    }
  }
}

object Migration {

  def apply[OldEvent:       EventDecoder: EventEncoder,
            OldBusEnvelope: Extractor,
            OldDLQEnvelope,
            NewEvent:       EventEncoder,
            NewBusEnvelope: Enveloper,
            NewDLQEnvelope](
    oldEventBus:      EventBus[OldEvent, OldBusEnvelope, OldDLQEnvelope],
    newEventBus:      EventBus[NewEvent, NewBusEnvelope, NewDLQEnvelope]
  )(implicit system:  ActorSystem,
    materializer:     ActorMaterializer,
    executionContext: ExecutionContext): Migration[OldEvent,
                                                   OldBusEnvelope,
                                                   OldDLQEnvelope,
                                                   NewEvent,
                                                   NewBusEnvelope,
                                                   NewDLQEnvelope] =
    new Migration(oldEventBus, newEventBus)
}
