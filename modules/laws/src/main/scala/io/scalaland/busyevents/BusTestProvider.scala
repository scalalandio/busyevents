package io.scalaland.busyevents

trait BusTestProvider {

  type BusEnvelope
  val busConfigurator:   EventBus.BusConfigurator[BusEnvelope]
  val busImplementation: String
}
