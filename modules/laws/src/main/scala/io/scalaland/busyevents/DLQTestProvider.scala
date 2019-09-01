package io.scalaland.busyevents

import cats.effect.{ Async, Resource, Sync }

trait DLQTestProvider extends TestProvider {

  /// name for specification descriptions
  def dlqImplementationName: String

  /// type used in tests
  type DLQEnvelope

  // implementations

  implicit def dlqExtractor: Extractor[DLQEnvelope]

  def dlqEnvironment[F[_]:  Async]: Resource[F, Unit]
  def dlqConfigurator[F[_]: Sync]:  Resource[F, EventBus.DeadLetterQueueConfigurator[DLQEnvelope]]

  // test utilities

  def dlqPublishDirectly[F[_]:              Async](events: List[DLQEnvelope]): F[Unit]
  def dlqFetchTopNotProcessedDirectly[F[_]: Async](): F[List[DLQEnvelope]]
}
