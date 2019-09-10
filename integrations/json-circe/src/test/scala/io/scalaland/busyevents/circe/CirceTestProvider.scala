package io.scalaland.busyevents
package circe

import org.scalacheck.{ Arbitrary, Gen }

trait CirceTestProvider extends CodecTestProvider {

  /// name for specification descriptions
  override def codecImplementationName: String = "Circe"

  /// type used in tests
  override type Event = CirceTest

  // implementations

  override implicit def decoder: EventDecoder[CirceTest] = fromCirceDecoder[CirceTest]
  override implicit def encoder: EventEncoder[CirceTest] = fromCirceEncoder[CirceTest]

  // test utilities

  override implicit def event: Arbitrary[CirceTest] = Arbitrary(
    for {
      s <- Gen.alphaNumStr
      i <- Gen.size
      d <- Gen.size.map(_.toDouble)
    } yield CirceTest(s, i, d)
  )
}
