package io.scalaland.busyevents.circe

import io.circe.generic.JsonCodec

@JsonCodec
final case class CirceTest(
  str: String,
  i:   Int,
  d:   Double
)
