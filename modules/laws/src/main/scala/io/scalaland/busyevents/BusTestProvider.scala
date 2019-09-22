package io.scalaland.busyevents

import cats.effect.{ Async, Resource, Timer }

trait BusTestProvider extends TestProvider {

  /// name for specification descriptions
  def busImplementationName: String

  /// type used in tests
  type BusEnvelope

  // implementations

  implicit def busEnveloper: Enveloper[BusEnvelope]
  implicit def busExtractor: Extractor[BusEnvelope]

  def busEnvironment[F[_]:  Async: Timer]: Resource[F, Unit]
  def busConfigurator[F[_]: Async]: Resource[F, EventBus.BusConfigurator[BusEnvelope]]

  // test utilities

  def isSafeForPublishing(msgSizes: Seq[Long]): Boolean
  def busPublishDirectly[F[_]:           Async](events: List[BusEnvelope]): F[Unit]
  def busFetchNotProcessedDirectly[F[_]: Async: Timer]: F[List[BusEnvelope]]
  def busMarkAllAsProcessed[F[_]:        Async: Timer]: F[Unit]
}
