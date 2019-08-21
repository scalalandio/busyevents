package io.scalaland.busyevents

import scala.concurrent.duration.FiniteDuration

final case class StreamConfig(
                         workerId: String,
                         minBackoff: FiniteDuration,
                         maxBackoff: FiniteDuration,
                         randomFactor: Double,
                         maxRetries: Int,
                         processorParallelism: Int
                       )
