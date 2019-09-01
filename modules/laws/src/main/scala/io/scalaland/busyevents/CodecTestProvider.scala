package io.scalaland.busyevents

import org.scalacheck.Arbitrary

trait CodecTestProvider extends TestProvider {

  /// name for specification descriptions
  def codecImplementationName: String

  /// type used in tests
  type Event

  // implementations

  implicit def decoder: EventDecoder[Event]
  implicit def encoder: EventEncoder[Event]

  // test utilities

  implicit def event: Arbitrary[Event]
  protected def events(size: Int): List[Event] =
    Stream.continually(event.arbitrary.sample.toList).flatten.take(size).toList
  protected def eventSizeInBytes(event: Event): Long = encoder(event).array().length.toLong
}
