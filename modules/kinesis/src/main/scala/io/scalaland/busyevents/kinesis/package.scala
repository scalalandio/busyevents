package io.scalaland.busyevents

package object kinesis {

  implicit def kinesisEnveloper(implicit keyGenerator: KinesisKeyGenerator): Enveloper[KinesisEnvelope] =
    (rawEvent: RawEvent) => KinesisEnvelope(keyGenerator(rawEvent), rawEvent, None)
  implicit val kinesisExtractor: Extractor[KinesisEnvelope] = (envelope: KinesisEnvelope) => envelope.byteBuffer
}
