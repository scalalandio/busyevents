package io.scalaland.busyevents
package aws

import java.net.URI

import software.amazon.awssdk.auth.credentials.{ AwsBasicCredentials, StaticCredentialsProvider }
import software.amazon.awssdk.core.SdkSystemSetting
import software.amazon.awssdk.http.Protocol
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.regions.Region

trait AWSTestProvider extends TestProvider {

  System.setProperty(SdkSystemSetting.CBOR_ENABLED.property, "false")

  def testConfig[C](testEndpoint: String): ClientConfig[C] = ClientConfig(
    httpClient          = NettyNioAsyncHttpClient.builder().protocol(Protocol.HTTP1_1).build(),
    credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create("mock", "mock")),
    endpointOverride    = Some(URI.create(testEndpoint)),
    region              = Some(Region.EU_CENTRAL_1)
  )
}
