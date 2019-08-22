package io.scalaland.busyevents

sealed trait EventDecodingResult[+Event] extends Product with Serializable
object EventDecodingResult {
  final case class Success[+Event](event: Event) extends EventDecodingResult[Event]
  final case class Failure(message:       String) extends EventDecodingResult[Nothing]
  final case object Skipped extends EventDecodingResult[Nothing]
}
