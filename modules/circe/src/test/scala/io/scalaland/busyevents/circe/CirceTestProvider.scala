package io.scalaland.busyevents
package circe

trait CirceTestProvider extends CodecTestProvider {

  override type Event = CirceTest
  override implicit val decoder:        EventDecoder[CirceTest] = fromCirceDecoder[CirceTest]
  override implicit val encoder:        EventEncoder[CirceTest] = fromCirceEncoder[CirceTest]
  override val codecImplementationName: String                  = "Circe"
}
