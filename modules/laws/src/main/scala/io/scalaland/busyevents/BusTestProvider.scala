package io.scalaland.busyevents

import cats.effect.{ Async, Resource, Sync }

trait BusTestProvider extends TestProvider {

  type BusEnvelope

  implicit def busEnveloper: Enveloper[BusEnvelope]
  implicit def busExtractor: Extractor[BusEnvelope]

  def busEnvironment[F[_]:  Async]: Resource[F, Unit]
  def busConfigurator[F[_]: Sync]:  Resource[F, EventBus.BusConfigurator[BusEnvelope]]

  def busImplementationName: String
}
