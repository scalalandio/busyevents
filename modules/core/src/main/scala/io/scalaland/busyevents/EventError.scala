package io.scalaland.busyevents

sealed trait EventError[+Envelope, +Event] extends Product with Serializable

object EventError {
  final case class DecodingError[Envelope](message: String, envelope) extends EventError[Envelope, Nothing]
  final case class ProcessingError[Event](message: String, event: Event) extends EventError[Nothing, Event]
}
