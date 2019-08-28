package io.scalaland.busyevents

import io.scalaland.busyevents.utils.RetryConfig

final case class StreamConfig(
  appName:              String,
  processorParallelism: Int,
  retryConfig:          RetryConfig = RetryConfig()
)
