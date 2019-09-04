package io.scalaland.busyevents
package aws

import cats.effect.{ Resource, Sync }
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.SqsAsyncClient

package object sqs {

  implicit val sqsExtractor: Extractor[Message] = (message: Message) => RawEvent.wrap(message.body().getBytes)

  implicit class AWSResourcesWithSQS(val awsResources: AWSResources.type) extends AnyVal {

    def sqs[F[_]: Sync](
      config: ClientConfig[SqsAsyncClient] = ClientConfig()
    ): Resource[F, SqsAsyncClient] =
      awsResources.resource[F, SqsAsyncClient](config.configure(SqsAsyncClient.builder()))
  }
}
