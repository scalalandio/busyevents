package io.scalaland.busyevents

trait CodecTestProvider {

  type Event
  implicit val decoder:    EventDecoder[Event]
  implicit val encoder:    EventEncoder[Event]
  val codecImplementation: String
}
