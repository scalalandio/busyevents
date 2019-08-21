package io.scalaland.busyevents

trait Extractor[Envelope] extends (Envelope => RawEvent)

object Extractor {

  @inline def apply[Envelope](implicit extractor: Extractor[Envelope]): Extractor[Envelope] = extractor
}
