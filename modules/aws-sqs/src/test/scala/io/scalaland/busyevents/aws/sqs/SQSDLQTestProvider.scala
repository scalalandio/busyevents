package io.scalaland.busyevents
package aws
package sqs

import cats.effect.{ Async, Resource, Sync }
import cats.implicits._
import software.amazon.awssdk.core.SdkSystemSetting
import software.amazon.awssdk.services.sqs.model.{ CreateQueueRequest, DeleteQueueRequest, Message }
import software.amazon.awssdk.services.sqs.SqsAsyncClient

trait SQSDLQTestProvider extends DLQTestProvider with AWSTestProvider {

  System.setProperty(SdkSystemSetting.CBOR_ENABLED.property, "false")

  private def sqsEndpoint  = "http://0.0.0.0:4503/"
  private def sqsQueueName = s"sqs-dlq-$providerId"
  private def sqsQueueUrl  = s"$sqsEndpoint/queue/dlqName"
  private def sqsConfig: ClientConfig[SqsAsyncClient] = testConfig(sqsEndpoint)

  override type DLQEnvelope = Message

  override def dlqExtractor: Extractor[DLQEnvelope] = sqsExtractor

  override def dlqEnvironment[F[_]: Async]: Resource[F, Unit] = {
    val sqsQueue = AWSResources.sqs[F](sqsConfig).flatMap { sqs =>
      Resource.make[F, Unit] {
        Async[F].delay(sqs.createQueue(CreateQueueRequest.builder().queueName(sqsQueueName).build())).void
      } { _ =>
        Async[F].delay(sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(sqsQueueUrl).build())).void
      }
    }
    sqsQueue.void
  }
  override def dlqConfigurator[F[_]: Sync]: Resource[F, EventBus.DeadLetterQueueConfigurator[Message]] =
    AWSResources.sqs[F](sqsConfig).map(SQSDeadLetterQueueConfigurator(SQSDeadLetterQueueConfig(sqsQueueUrl), log))

  override def dlqImplementationName = "SQS"
}
