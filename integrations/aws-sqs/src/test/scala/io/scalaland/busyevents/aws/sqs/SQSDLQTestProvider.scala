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

  private def sqsEndpoint  = "http://0.0.0.0:4503/"
  private def sqsQueueName = s"sqs-dlq-$providerId"
  private def sqsQueueUrl  = s"$sqsEndpoint/queue/dlqName"
  private def sqsConfig: ClientConfig[SqsAsyncClient] = testConfig(sqsEndpoint)

  /// type used in tests
  override type DLQEnvelope = Message

  // implementations

  override def dlqExtractor: Extractor[DLQEnvelope] = sqsExtractor

  private var client: SqsAsyncClient = _
  override def dlqEnvironment[F[_]: Async]: Resource[F, Unit] = {
    val sqsQueue = AWSResources
      .sqs[F](sqsConfig)
      .map { sqs =>
        client = sqs
        sqs
      }
      .flatMap { sqs =>
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

  // test utilities

  override def dlqPublishDirectly[F[_]: Async](events: List[DLQEnvelope]): F[Unit] = Async[F].defer {
    client
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
      .void
  }
  override def dlqFetchTopNotProcessedDirectly[F[_]: Async](): F[List[DLQEnvelope]] = Async[F].defer {
    client
      .receiveMessage(ReceiveMessageRequest.builder().queueUrl(sqsQueueUrl).maxNumberOfMessages(10).build())
      .toScala
      .asAsync[F]
      .map(_.messages().asScala.toList)
  }
  override def dlqMarkAllAsProcessed[F[_]: Async]: F[Unit] = Async[F].defer {
    ().pure[F] // TODO: implement it for tests
  }
}
