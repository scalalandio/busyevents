package io.scalaland.busyevents.kinesis

import java.net.URI

import cats.effect.{ Resource, Sync }
import software.amazon.awssdk.auth.credentials.{ AwsCredentialsProvider, DefaultCredentialsProvider }
import software.amazon.awssdk.awscore.client.builder.{ AwsAsyncClientBuilder, AwsClientBuilder }
import software.amazon.awssdk.core.SdkClient
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.http.Protocol
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient

object KinesisResources {

  final case class ClientConfig[C](
    httpClient:          SdkAsyncHttpClient     = NettyNioAsyncHttpClient.builder().protocol(Protocol.HTTP1_1).build(),
    credentialsProvider: AwsCredentialsProvider = DefaultCredentialsProvider.create(),
    region:              Option[Region]         = None,
    endpointOverride:    Option[URI]            = None
  ) {

    def configure[B <: AwsAsyncClientBuilder[B, C] with AwsClientBuilder[B, C]](builder: B): C = {
      implicit class OptionalOps[T](val a: T) {

        def optionally[S](bOpt: Option[S])(f: S => T => T): T = bOpt.map(f(_)(a)).getOrElse(a)
      }

      builder
        .httpClient(httpClient)
        .credentialsProvider(credentialsProvider)
        .optionally(region)(r => _.region(r))
        .optionally(endpointOverride)(e => _.endpointOverride(e))
        .build()
    }
  }

  private def resource[F[_]: Sync, A <: SdkClient](thunk: => A): Resource[F, A] =
    Resource.fromAutoCloseable[F, A](Sync[F].delay(thunk))

  def kinesis[F[_]: Sync](
    config: ClientConfig[KinesisAsyncClient] = ClientConfig()
  ): Resource[F, KinesisAsyncClient] =
    resource[F, KinesisAsyncClient](config.configure(KinesisAsyncClient.builder()))

  def dynamo[F[_]: Sync](
    config: ClientConfig[DynamoDbAsyncClient] = ClientConfig()
  ): Resource[F, DynamoDbAsyncClient] =
    resource[F, DynamoDbAsyncClient](config.configure(DynamoDbAsyncClient.builder()))

  def cloudWatch[F[_]: Sync](
    config: ClientConfig[CloudWatchAsyncClient] = ClientConfig()
  ): Resource[F, CloudWatchAsyncClient] =
    resource[F, CloudWatchAsyncClient](config.configure(CloudWatchAsyncClient.builder()))
}
