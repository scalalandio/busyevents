package io.scalaland.busyevents

final class Publisher[F[_], Envelope](publishing: List[Envelope] => F[Unit]) {

  def publishEvent(event: Envelope): F[Unit] = publishing(List(event))

  def publishEvents(events: List[Envelope]): F[Unit] = publishing(events)
}
