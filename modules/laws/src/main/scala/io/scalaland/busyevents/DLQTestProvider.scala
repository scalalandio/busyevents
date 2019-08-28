package io.scalaland.busyevents

import cats.effect.{ Async, Resource, Sync }

trait DLQTestProvider {

  type DLQEnvelope

  implicit val dlqExtractor: Extractor[DLQEnvelope]

  def dlqEnvironment[F[_]:  Async]: Resource[F, Unit]
  def dlqConfigurator[F[_]: Sync]:  Resource[F, EventBus.DeadLetterQueueConfigurator[DLQEnvelope]]

  val dlqImplementationName: String
}
