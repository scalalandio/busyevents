package io.scalaland.busyevents
package aws

import cats.effect.{ Resource, Sync }
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient

package object kinesis {

  implicit def kinesisEnveloper(implicit keyGenerator: KinesisKeyGenerator): Enveloper[KinesisEnvelope] =
    (rawEvent: RawEvent) => KinesisEnvelope(keyGenerator(rawEvent), rawEvent, None)
  implicit val kinesisExtractor: Extractor[KinesisEnvelope] = (envelope: KinesisEnvelope) => envelope.byteBuffer

  implicit class AWSResourcesWithSQS(val awsResources: AWSResources.type) extends AnyVal {

    def kinesis[F[_]: Sync](
      config: ClientConfig[KinesisAsyncClient] = ClientConfig()
    ): Resource[F, KinesisAsyncClient] =
      awsResources.resource[F, KinesisAsyncClient](config.configure(KinesisAsyncClient.builder()))

    def dynamo[F[_]: Sync](
      config: ClientConfig[DynamoDbAsyncClient] = ClientConfig()
    ): Resource[F, DynamoDbAsyncClient] =
      awsResources.resource[F, DynamoDbAsyncClient](config.configure(DynamoDbAsyncClient.builder()))

    def cloudWatch[F[_]: Sync](
      config: ClientConfig[CloudWatchAsyncClient] = ClientConfig()
    ): Resource[F, CloudWatchAsyncClient] =
      awsResources.resource[F, CloudWatchAsyncClient](config.configure(CloudWatchAsyncClient.builder()))
  }
}
