package io.scalaland.busyevents

import org.specs2.mutable.Specification

trait EventBusSpecification extends Specification { self: CodecTestProvider with BusTestProvider with DLQTestProvider =>

}
