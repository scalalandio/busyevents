package io.scalaland.busyevents.utils

import scala.concurrent.duration.FiniteDuration

final case class RetryConfig(
  minBackoff:   FiniteDuration,
  maxBackoff:   FiniteDuration,
  randomFactor: Double,
  maxRetries:   Int
)
