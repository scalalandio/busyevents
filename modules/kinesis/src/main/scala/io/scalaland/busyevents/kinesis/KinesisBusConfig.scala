package io.scalaland.busyevents.kinesis

import software.amazon.kinesis.common.InitialPositionInStream
import software.amazon.kinesis.metrics.MetricsLevel

final case class KinesisBusConfig(
  appName:                       String,
  kinesisStreamName:             String,
  dynamoTableName:               String,
  parentShardPollIntervalMillis: Long = 250L,
  metricsLevel:                  MetricsLevel = MetricsLevel.SUMMARY,
  initialPositionInStream:       InitialPositionInStream = InitialPositionInStream.TRIM_HORIZON
)
