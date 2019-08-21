package io.scalaland.busyevents

trait EventDecoder[Event] extends (RawEvent => EventDecodingResult[Event])

object EventDecoder {

  @inline def apply[Event](implicit eventDecoder: EventDecoder[Event]): EventDecoder[Event] = eventDecoder
}
