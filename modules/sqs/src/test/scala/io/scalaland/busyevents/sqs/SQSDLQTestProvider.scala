package io.scalaland.busyevents
package sqs

import cats.effect.SyncIO
import com.typesafe.scalalogging.Logger
import software.amazon.awssdk.services.sqs.model.Message

trait SQSDLQTestProvider extends DLQTestProvider with ResourcesSpec {

  val log: Logger

  override type DLQEnveloper = Message
  override val dlqConfigurator: EventBus.DeadLetterQueueConfigurator[Message] = useResource {
    // TODO: this queue URL is invalid - fix me once everything else it set up
    SQSResources.sqs[SyncIO]().map(SQSDeadLetterQueueConfigurator(SQSDeadLetterQueueConfig("sqs-dlq"), log))
  }
  override val dlqImplementation = "SQS"
}
