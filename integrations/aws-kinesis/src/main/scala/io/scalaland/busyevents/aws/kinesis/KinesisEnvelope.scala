package io.scalaland.busyevents.aws.kinesis

import java.nio.ByteBuffer

import akka.Done

import scala.concurrent.Future

final case class KinesisEnvelope(
  key:        String,
  byteBuffer: ByteBuffer,
  commit:     Option[() => Future[Done]]
)

object KinesisEnvelope {

  def fromKinesisStreamRecord(record: px.kinesis.stream.consumer.Record): KinesisEnvelope =
    apply(record.key, record.data.asByteBuffer, Option(record.markProcessed))

  def fromKinesisRecord(record: software.amazon.awssdk.services.kinesis.model.Record): KinesisEnvelope =
    apply(record.partitionKey, record.data.asByteBuffer, None)
}
