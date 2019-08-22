package io.scalaland.busyevents

trait Enveloper[Envelope] extends (RawEvent => Envelope)

object Enveloper {

  @inline def apply[Envelope](implicit enveloper: Enveloper[Envelope]): Enveloper[Envelope] = enveloper
}
