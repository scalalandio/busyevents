package io.scalaland.busyevents

import cats.effect.{ Async, Resource, Sync }

trait BusTestProvider extends TestProvider {

  /// name for specification descriptions
  def busImplementationName: String

  /// type used in tests
  type BusEnvelope

  // implementations

  implicit def busEnveloper: Enveloper[BusEnvelope]
  implicit def busExtractor: Extractor[BusEnvelope]

  def busEnvironment[F[_]:  Async]: Resource[F, Unit]
  def busConfigurator[F[_]: Sync]:  Resource[F, EventBus.BusConfigurator[BusEnvelope]]

  // test utilities

  def isSafeForPublishing(msgSizes: List[Long]): Boolean
  def busPublishDirectly[F[_]:           Async](events: List[BusEnvelope]): F[Unit]
  def busFetchNotProcessedDirectly[F[_]: Async](): F[List[BusEnvelope]]
}
