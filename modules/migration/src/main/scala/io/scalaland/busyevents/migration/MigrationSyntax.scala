package io.scalaland.busyevents
package migration

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer

import scala.concurrent.ExecutionContext

trait MigrationSyntax {

  // scalastyle:off
  implicit class EventBusMigrationSyntax[
    OldEvent:       EventDecoder: EventEncoder,
    OldBusEnvelope: Extractor,
    OldDLQEnvelope
  ](
    oldEventBus: EventBus[OldEvent, OldBusEnvelope, OldDLQEnvelope]
  ) {

    def migrateTo[NewEvent: EventEncoder, NewBusEnvelope: Enveloper, NewDLQEnvelope](
      newEventBus:      EventBus[NewEvent, NewBusEnvelope, NewDLQEnvelope]
    )(implicit system:  ActorSystem,
      materializer:     ActorMaterializer,
      executionContext: ExecutionContext): Migration[OldEvent,
                                                     OldBusEnvelope,
                                                     OldDLQEnvelope,
                                                     NewEvent,
                                                     NewBusEnvelope,
                                                     NewDLQEnvelope] =
      Migration(oldEventBus, newEventBus)
  }
  // scalastyle:on
}
