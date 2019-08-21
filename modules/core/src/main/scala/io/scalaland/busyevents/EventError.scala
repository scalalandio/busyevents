package io.scalaland.busyevents

sealed trait EventError[+Envelope, +Event] extends Product with Serializable

object EventError {

  final case class DecodingError[Envelope](message: String, envelope: Envelope) extends EventError[Envelope, Nothing]

  final case class ProcessingError[Event](message: String, event: Event, throwable: Option[Throwable])
      extends EventError[Nothing, Event]
}
