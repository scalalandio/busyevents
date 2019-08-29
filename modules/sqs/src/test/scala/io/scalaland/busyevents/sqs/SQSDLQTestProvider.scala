package io.scalaland.busyevents
package sqs

import java.net.URI

import cats.effect.{ Async, Resource, Sync }
import cats.implicits._
import io.scalaland.busyevents.sqs.SQSResources.ClientConfig
import software.amazon.awssdk.auth.credentials.{ AwsBasicCredentials, StaticCredentialsProvider }
import software.amazon.awssdk.core.SdkSystemSetting
import software.amazon.awssdk.services.sqs.model.{ CreateQueueRequest, DeleteQueueRequest, Message }
import software.amazon.awssdk.services.sqs.SqsAsyncClient

trait SQSDLQTestProvider extends DLQTestProvider {

  System.setProperty(SdkSystemSetting.CBOR_ENABLED.property, "false")

  private def sqsEndpoint  = "http://0.0.0.0:4503/"
  private def sqsQueueName = s"sqs-dlq-$providerId"
  private def sqsQueueUrl  = s"$sqsEndpoint/queue/dlqName"
  private def sqsConfig: ClientConfig[SqsAsyncClient] =
    ClientConfig(
      credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create("mock", "mock")),
      endpointOverride    = Some(URI.create(sqsEndpoint))
    )

  override type DLQEnvelope = Message

  override def dlqExtractor: Extractor[DLQEnvelope] = sqsExtractor

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

  override def dlqImplementationName = "SQS"
}
