package io.scalaland.busyevents

import cats.effect.{ Async, Resource, Sync }

trait BusTestProvider extends TestProvider {

  type BusEnvelope

  implicit val busEnveloper: Enveloper[BusEnvelope]
  implicit val busExtractor: Extractor[BusEnvelope]

  def busEnvironment[F[_]:  Async]: Resource[F, Unit]
  def busConfigurator[F[_]: Sync]:  Resource[F, EventBus.BusConfigurator[BusEnvelope]]

  val busImplementationName: String
}
