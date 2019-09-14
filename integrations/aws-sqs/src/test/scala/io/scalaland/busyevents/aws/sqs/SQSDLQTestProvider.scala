package io.scalaland.busyevents
package aws
package sqs

import cats.effect.{ Async, Resource, Sync }
import cats.implicits._
import io.scalaland.busyevents.utils.FutureToAsync
import software.amazon.awssdk.core.SdkSystemSetting
import software.amazon.awssdk.services.sqs.model.{
  CreateQueueRequest,
  DeleteQueueRequest,
  Message,
  PurgeQueueRequest,
  ReceiveMessageRequest,
  SendMessageBatchRequest,
  SendMessageBatchRequestEntry
}
import software.amazon.awssdk.services.sqs.SqsAsyncClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.JavaConverters._
import scala.compat.java8.FutureConverters._

trait SQSDLQTestProvider extends DLQTestProvider with AWSTestProvider {

  System.setProperty(SdkSystemSetting.CBOR_ENABLED.property, "false")

  /// name for specification descriptions
  override def dlqImplementationName = "SQS"

  private def sqsEndpoint  = DockerServices.sqs
  private def sqsQueueName = s"sqs-dlq-$providerId"
  private def sqsQueueUrl  = s"${sqsEndpoint}queue/$sqsQueueName"
  private def sqsConfig: ClientConfig[SqsAsyncClient] = testConfig(sqsEndpoint)

  /// type used in tests
  override type DLQEnvelope = Message

  // implementations

  override def dlqExtractor: Extractor[DLQEnvelope] = sqsExtractor

  private var sqsAsyncClient: SqsAsyncClient = _
  override def dlqEnvironment[F[_]: Async]: Resource[F, Unit] = {
    val sqsQueue = loggedResourceFrom(s"SqsAsyncClient to $sqsEndpoint")(AWSResources.sqs[F](sqsConfig))
      .map { sqs =>
        sqsAsyncClient = sqs
        sqs
      }
      .flatTap { sqs =>
        loggedResource[F, Unit](s"SQS queue '$sqsQueueName'") {
          Async[F].delay(sqs.createQueue(CreateQueueRequest.builder().queueName(sqsQueueName).build())).void
        } { _ =>
          Async[F].delay(sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(sqsQueueUrl).build())).void
        }
      }
    sqsQueue.void
  }
  override def dlqConfigurator[F[_]: Sync]: Resource[F, EventBus.DeadLetterQueueConfigurator[Message]] =
    loggedResourceFrom("SQSDeadLetterQueueConfigurator") {
      loggedResourceFrom(s"SqsAsyncClient2 to $sqsEndpoint")(AWSResources.sqs[F](sqsConfig))
        .map(SQSDeadLetterQueueConfigurator(SQSDeadLetterQueueConfig(sqsQueueUrl), log))
    }

  // test utilities

  override def dlqPublishDirectly[F[_]: Async](events: List[DLQEnvelope]): F[Unit] =
    Async[F].defer {
      sqsAsyncClient
        .sendMessageBatch(
          SendMessageBatchRequest
            .builder()
            .queueUrl(sqsQueueUrl)
            .entries(
              events.map(e => SendMessageBatchRequestEntry.builder().messageBody(e.body()).build()).asJavaCollection
            )
            .build()
        )
        .toScala
        .asAsync[F]
    }.void
  override def dlqFetchTopNotProcessedDirectly[F[_]: Async](): F[List[DLQEnvelope]] = Async[F].defer {
    sqsAsyncClient
      .receiveMessage(ReceiveMessageRequest.builder().queueUrl(sqsQueueUrl).maxNumberOfMessages(10).build())
      .toScala
      .asAsync[F]
      .map(_.messages().asScala.toList)
  }
  override def dlqMarkAllAsProcessed[F[_]: Async]: F[Unit] =
    Async[F].defer {
      sqsAsyncClient.purgeQueue(PurgeQueueRequest.builder().queueUrl(sqsQueueUrl).build()).toScala.asAsync[F]
    }.void
}
