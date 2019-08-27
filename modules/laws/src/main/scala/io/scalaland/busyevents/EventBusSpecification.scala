package io.scalaland.busyevents

import com.typesafe.scalalogging.Logger
import org.specs2.mutable.Specification

trait EventBusSpecification extends Specification { self: CodecTestProvider with BusTestProvider with DLQTestProvider =>

  val log: Logger = Logger(s"event-bus.$codecImplementation-$busImplementation-$dlqImplementation")

  // TODO: implement tests
}
