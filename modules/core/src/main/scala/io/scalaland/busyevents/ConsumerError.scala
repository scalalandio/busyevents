package io.scalaland.busyevents

sealed trait ConsumerError[+Event] extends Product with Serializable

object ConsumerError {

  final case class DecodingError(message: String, rawEvent: RawEvent) extends ConsumerError[Nothing]

  final case class ProcessingError[Event](message: String, event: Event, throwable: Option[Throwable])
      extends ConsumerError[Event]
}
