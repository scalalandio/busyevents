package io.scalaland.busyevents

trait EventEncoder[Event] extends (Event => RawEvent)

object EventEncoder {

  @inline def apply[Event](implicit eventEncoder: EventEncoder[Event]): EventEncoder[Event] = eventEncoder
}
