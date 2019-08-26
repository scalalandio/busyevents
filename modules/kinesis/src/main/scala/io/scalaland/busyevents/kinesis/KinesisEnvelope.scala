package io.scalaland.busyevents.kinesis

import java.nio.ByteBuffer

import akka.Done

import scala.concurrent.Future

final case class KinesisEnvelope(
  key:        String,
  byteBuffer: ByteBuffer,
  commit:     Option[() => Future[Done]]
)
