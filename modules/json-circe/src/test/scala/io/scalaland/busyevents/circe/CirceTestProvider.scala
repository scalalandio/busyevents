package io.scalaland.busyevents
package circe

trait CirceTestProvider extends CodecTestProvider {

  override type Event = CirceTest
  override implicit def decoder:        EventDecoder[CirceTest] = fromCirceDecoder[CirceTest]
  override implicit def encoder:        EventEncoder[CirceTest] = fromCirceEncoder[CirceTest]
  override def codecImplementationName: String                  = "Circe"
}
