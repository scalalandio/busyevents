package io.scalaland.busyevents
package aws

import java.net.URI

import software.amazon.awssdk.auth.credentials.{ AwsCredentialsProvider, DefaultCredentialsProvider }
import software.amazon.awssdk.awscore.client.builder.{ AwsAsyncClientBuilder, AwsClientBuilder }
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.http.Protocol
import software.amazon.awssdk.regions.Region

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
