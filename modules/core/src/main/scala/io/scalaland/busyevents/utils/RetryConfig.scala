package io.scalaland.busyevents.utils

import scala.concurrent.duration._

final case class RetryConfig(
  minBackoff:   FiniteDuration = 1.second,
  maxBackoff:   FiniteDuration = 1.minute,
  randomFactor: Double         = 2.0,
  maxRetries:   Int            = 10 // scalastyle:ignore magic.number
)
