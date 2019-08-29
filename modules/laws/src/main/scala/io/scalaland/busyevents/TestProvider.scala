package io.scalaland.busyevents

import java.util.UUID

import com.typesafe.scalalogging.Logger

trait TestProvider {

  val providerId: UUID = UUID.randomUUID()

  def log: Logger
}
