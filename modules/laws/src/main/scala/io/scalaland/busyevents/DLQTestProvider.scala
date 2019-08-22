package io.scalaland.busyevents

trait DLQTestProvider {

  type DLQEnveloper
  val dlqConfigurator:   EventBus.DeadLetterQueueConfigurator[DLQEnveloper]
  val dlqImplementation: String
}
