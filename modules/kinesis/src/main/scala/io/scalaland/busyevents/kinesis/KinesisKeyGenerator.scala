package io.scalaland.busyevents
package kinesis

trait KinesisKeyGenerator extends (RawEvent => String)
