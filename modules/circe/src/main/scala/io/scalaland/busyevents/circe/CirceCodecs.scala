package io.scalaland.busyevents
package circe

import io.circe.{ Decoder, Encoder }

trait CirceCodecs {

  implicit def fromCirceDecoder[A: Decoder]: EventDecoder[A] =
    (rawEvent: RawEvent) =>
      io.circe.jawn.parseByteBuffer(rawEvent).flatMap(Decoder[A].decodeJson) match {
        case Left(error)  => EventDecodingResult.Failure(error.getMessage)
        case Right(value) => EventDecodingResult.Success(value)
    }

  implicit def fromCirceEncoder[A: Encoder]: EventEncoder[A] =
    (event: A) => RawEvent.wrap(Encoder[A].apply(event).noSpaces.getBytes)
}
