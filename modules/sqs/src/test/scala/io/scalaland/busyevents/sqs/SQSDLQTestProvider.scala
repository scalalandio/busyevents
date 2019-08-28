package io.scalaland.busyevents
package sqs

import cats.effect.{ Async, Resource, Sync }
import com.typesafe.scalalogging.Logger
import software.amazon.awssdk.services.sqs.model.Message

trait SQSDLQTestProvider extends DLQTestProvider {

  // TODO: add setup and teardown!!
  // TODO: creation of queue in SQS
  // TODO: deletion of queue in SQS

  val log: Logger

  override type DLQEnvelope = Message

  override val dlqExtractor: Extractor[DLQEnvelope] = sqsExtractor

  override def dlqEnvironment[F[_]:  Async]: Resource[F, Unit]                                          = Resource.pure(())
  override def dlqConfigurator[F[_]: Sync]:  Resource[F, EventBus.DeadLetterQueueConfigurator[Message]] =
    // TODO: this queue URL is invalid - fix me once everything else it set up
    SQSResources.sqs[F]().map(SQSDeadLetterQueueConfigurator(SQSDeadLetterQueueConfig("sqs-dlq"), log))

  override val dlqImplementationName = "SQS"
}
