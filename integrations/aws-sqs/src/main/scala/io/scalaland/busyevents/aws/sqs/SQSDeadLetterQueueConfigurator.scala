package io.scalaland.busyevents
package aws
package sqs

import java.nio.charset.StandardCharsets

import akka.actor.ActorSystem
import akka.stream.{ ActorMaterializer, SharedKillSwitch }
import akka.stream.scaladsl._
import akka.NotUsed
import akka.stream.alpakka.sqs.scaladsl._
import akka.stream.alpakka.sqs.{ MessageAction, SqsAckSettings, SqsPublishSettings, SqsSourceSettings }
import com.typesafe.scalalogging.Logger
import software.amazon.awssdk.services.sqs.model.{ Message, SendMessageRequest }
import software.amazon.awssdk.services.sqs.SqsAsyncClient

import scala.concurrent.ExecutionContext

class SQSDeadLetterQueueConfigurator(
  sqsDeadLetterQueueConfig: SQSDeadLetterQueueConfig,
  log:                      Logger,
  sqsPublishSettings:       SqsPublishSettings,
  sqsSourceSettings:        SqsSourceSettings,
  sqsAckSettings:           SqsAckSettings,
  sqsAsyncClient:           SqsAsyncClient
) extends EventBus.DeadLetterQueueConfigurator[Message] {

  import sqsDeadLetterQueueConfig._

  override def deadLetterEnqueue[Event: EventEncoder](
    implicit system: ActorSystem,
    materializer:    ActorMaterializer,
    ec:              ExecutionContext
  ): Flow[ConsumerError[Event], Unit, NotUsed] = {
    def enqueue(eventError: ConsumerError[Event]): Source[Unit, NotUsed] =
      Source.single[(SendMessageRequest, String)] {
        val encoded = new String(
          (eventError match {
            case ConsumerError.DecodingError(_, rawEvent) =>
              rawEvent
            case ConsumerError.ProcessingError(_, event, _) =>
              EventEncoder[Event].apply(event)
          }).array,
          StandardCharsets.UTF_8
        )
        SendMessageRequest.builder().messageBody(encoded).build() -> encoded
      } via SqsPublishFlow(queueUrl, sqsPublishSettings)(sqsAsyncClient).withContext[String] map {
        case (sent, encoded) =>
          eventError match {
            case ConsumerError.DecodingError(msg, _) =>
              log.error(s"Failed to decode event: '$encoded' due to error: $msg")
            case ConsumerError.ProcessingError(msg, _, None) =>
              log.error(s"Failed to process event: '$encoded' due to error: $msg")
            case ConsumerError.ProcessingError(msg, _, Some(error)) =>
              log.error(s"Failed to process event: '$encoded' due to error: $msg", error)
          }
          log.info(s"Message ${sent.result.messageId} enqueued on SQS")
      }

    Flow[ConsumerError[Event]].flatMapConcat(enqueue)
  }

  override def deadLetterEvents(
    implicit system: ActorSystem,
    materializer:    ActorMaterializer,
    ec:              ExecutionContext
  ): (String, SharedKillSwitch) => Source[Message, NotUsed] =
    (_, killSwitch) => SqsSource(queueUrl, sqsSourceSettings)(sqsAsyncClient) via killSwitch.flow

  override def deadLetterDequeue(
    implicit system: ActorSystem,
    materializer:    ActorMaterializer,
    ec:              ExecutionContext
  ): Sink[Message, NotUsed] =
    Flow[Message]
      .map(MessageAction.delete)
      .via(SqsAckFlow(queueUrl, sqsAckSettings)(sqsAsyncClient))
      .map { ack =>
        log.info(s"Event ${ack.messageAction.message.messageId} dequeued from SQS")
      }
      .to(Sink.ignore)
}

object SQSDeadLetterQueueConfigurator {

  /** Useful if you wanted to make something like:
    *
    * {{{
    * (sqsResource).map(SQSDealLetterQueueConfigurator(config, log))
    * }}}
    */
  def apply(sqsDeadLetterQueueConfig: SQSDeadLetterQueueConfig,
            log:                      Logger,
            sqsPublishSettings:       SqsPublishSettings = SqsPublishSettings(),
            sqsSourceSettings:        SqsSourceSettings = SqsSourceSettings(),
            sqsAckSettings:           SqsAckSettings = SqsAckSettings())(
    sqsAsyncClient:                   SqsAsyncClient
  ): SQSDeadLetterQueueConfigurator = new SQSDeadLetterQueueConfigurator(
    sqsDeadLetterQueueConfig,
    log,
    sqsPublishSettings,
    sqsSourceSettings,
    sqsAckSettings,
    sqsAsyncClient
  )
}
