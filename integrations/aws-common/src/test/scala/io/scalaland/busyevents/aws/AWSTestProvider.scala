package io.scalaland.busyevents
package aws

import java.net.URI

import software.amazon.awssdk.auth.credentials.{ AwsBasicCredentials, StaticCredentialsProvider }
import software.amazon.awssdk.core.SdkSystemSetting
import software.amazon.awssdk.http.{ Protocol, SdkHttpConfigurationOption }
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.utils.AttributeMap

trait AWSTestProvider extends TestProvider {

  System.setProperty(SdkSystemSetting.CBOR_ENABLED.property, "false")

  def testConfig[C](testEndpoint: String): ClientConfig[C] = ClientConfig(
    httpClient          = NettyNioAsyncHttpClient.builder().protocol(Protocol.HTTP1_1).buildWithDefaults(
      AttributeMap
        .builder()
        .put(SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES, java.lang.Boolean.TRUE)
        .build()
    ),
    credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create("mock", "mock")),
    endpointOverride    = Some(URI.create(testEndpoint))
  )
}
