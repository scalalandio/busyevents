package io.scalaland.busyevents

trait CodecTestProvider extends TestProvider {

  type Event

  implicit def decoder: EventDecoder[Event]
  implicit def encoder: EventEncoder[Event]

  def codecImplementationName: String
}
