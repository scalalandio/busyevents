package io.scalaland.busyevents

import cats.MonadError
import cats.implicits._

final class Publisher[F[_]: MonadError[?[_], Throwable], Envelope: Enveloper, Event: EventEncoder](
  publishing: List[Envelope] => F[Unit]
) {

  private val event2envelope = EventEncoder[Event] andThen Enveloper[Envelope]

  def publishEvent(event: Event): F[Unit] = publishing(List(event2envelope(event)))

  def publishEvents(events: List[Event]): F[Unit] = publishing(events.map(event2envelope)).recoverWith {
    case error: Throwable =>
      println("failed to publish")
      PublisherError(events, error).raiseError[F, Unit]
  }
}
