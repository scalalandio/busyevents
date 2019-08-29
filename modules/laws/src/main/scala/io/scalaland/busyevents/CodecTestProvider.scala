package io.scalaland.busyevents

trait CodecTestProvider extends TestProvider {

  type Event

  implicit val decoder: EventDecoder[Event]
  implicit val encoder: EventEncoder[Event]

  val codecImplementationName: String
}
