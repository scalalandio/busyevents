package io.scalaland.busyevents

sealed trait EventError[+Event] extends Product with Serializable

object EventError {

  final case class DecodingError(message: String, rawEvent: RawEvent) extends EventError[Nothing]

  final case class ProcessingError[Event](message: String, event: Event, throwable: Option[Throwable])
      extends EventError[Event]
}
