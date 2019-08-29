package io.scalaland.busyevents

import cats.effect.{ Async, Resource, Sync }

trait DLQTestProvider extends TestProvider {

  type DLQEnvelope

  implicit def dlqExtractor: Extractor[DLQEnvelope]

  def dlqEnvironment[F[_]:  Async]: Resource[F, Unit]
  def dlqConfigurator[F[_]: Sync]:  Resource[F, EventBus.DeadLetterQueueConfigurator[DLQEnvelope]]

  def dlqImplementationName: String
}
