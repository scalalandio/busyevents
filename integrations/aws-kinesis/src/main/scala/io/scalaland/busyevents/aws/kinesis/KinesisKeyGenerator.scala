package io.scalaland.busyevents
package aws
package kinesis

trait KinesisKeyGenerator extends (RawEvent => String)
