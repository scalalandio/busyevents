package io.scalaland.busyevents
package sqs

import java.net.URI

import cats.effect.{ Async, Resource, Sync }
import cats.implicits._
import io.scalaland.busyevents.sqs.SQSResources.ClientConfig
import software.amazon.awssdk.services.sqs.model.{ CreateQueueRequest, DeleteQueueRequest, Message }
import software.amazon.awssdk.services.sqs.SqsAsyncClient

trait SQSDLQTestProvider extends DLQTestProvider {

  private val sqsEndpoint  = "http://0.0.0.0:4503/"
  private val sqsQueueName = s"sqs-dlq-$providerId"
  private val sqsQueueUrl  = s"$sqsEndpoint/queue/dlqName"
  private val sqsConfig: ClientConfig[SqsAsyncClient] =
    ClientConfig().copy(endpointOverride = Some(URI.create(sqsEndpoint)))

  override type DLQEnvelope = Message

  override val dlqExtractor: Extractor[DLQEnvelope] = sqsExtractor

  override def dlqEnvironment[F[_]: Async]: Resource[F, Unit] = {
    val sqsQueue = SQSResources.sqs[F](sqsConfig).flatMap { sqs =>
      Resource.make[F, Unit] {
        Async[F].delay(sqs.createQueue(CreateQueueRequest.builder().queueName(sqsQueueName).build())).void
      } { _ =>
        Async[F].delay(sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(sqsQueueUrl).build())).void
      }
    }
    sqsQueue.void
  }
  override def dlqConfigurator[F[_]: Sync]: Resource[F, EventBus.DeadLetterQueueConfigurator[Message]] =
    SQSResources.sqs[F](sqsConfig).map(SQSDeadLetterQueueConfigurator(SQSDeadLetterQueueConfig(sqsQueueUrl), log))

  override val dlqImplementationName = "SQS"
}
