package io.scalaland.busyevents
package kinesis

import java.net.URI

import cats.effect.{ Async, Resource, Sync }
import cats.implicits._
import io.scalaland.busyevents.kinesis.KinesisResources.ClientConfig
import software.amazon.awssdk.auth.credentials.{ AwsBasicCredentials, StaticCredentialsProvider }
import software.amazon.awssdk.core.SdkSystemSetting
import software.amazon.awssdk.services.dynamodb.model.{ CreateTableRequest, DeleteTableRequest }
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.kinesis.model.{ CreateStreamRequest, DeleteStreamRequest }
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient

trait KinesisBusTestProvider extends BusTestProvider {

  System.setProperty(SdkSystemSetting.CBOR_ENABLED.property, "false")

  private def kinesisEndpoint = "http://0.0.0.0:4501/"
  private def dynamoEndpoint  = "http://0.0.0.0:4502/"
  private def appName         = s"kinesis-test-$providerId"
  private def streamName      = s"kinesis-bus-$providerId"
  private def dynamoTableName = s"kinesis-bus-check-$providerId"
  private def kinesisConfig: ClientConfig[KinesisAsyncClient] =
    ClientConfig(
      credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create("mock", "mock")),
      endpointOverride    = Some(URI.create(kinesisEndpoint))
    )
  private def dynamoConfig: ClientConfig[DynamoDbAsyncClient] =
    ClientConfig(
      credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create("mock", "mock")),
      endpointOverride    = Some(URI.create(dynamoEndpoint))
    )

  override type BusEnvelope = KinesisEnvelope

  override def busEnveloper: Enveloper[BusEnvelope] = kinesisEnveloper(_ => "same-key")
  override def busExtractor: Extractor[BusEnvelope] = kinesisExtractor

  override def busEnvironment[F[_]: Async]: Resource[F, Unit] = {
    val kinesisStream = KinesisResources.kinesis[F](kinesisConfig).flatMap { kinesis =>
      Resource.make[F, Unit] {
        Async[F].delay {
          kinesis.createStream(CreateStreamRequest.builder().streamName(streamName).shardCount(1).build())
        }.void
      } { _ =>
        Async[F].delay {
          kinesis.deleteStream(DeleteStreamRequest.builder().streamName(streamName).build())
        }.void
      }
    }
    val dynamoTable = KinesisResources.dynamo[F](dynamoConfig).flatMap { dynamo =>
      Resource.make[F, Unit] {
        Async[F].delay {
          dynamo.createTable(CreateTableRequest.builder().tableName(dynamoTableName).build())
        }.void
      } { _ =>
        Async[F].delay {
          dynamo.deleteTable(DeleteTableRequest.builder().tableName(dynamoTableName).build())
        }.void
      }
    }
    (kinesisStream, dynamoTable).tupled.void
  }
  override def busConfigurator[F[_]: Sync]: Resource[F, EventBus.BusConfigurator[KinesisEnvelope]] =
    (
      KinesisResources.kinesis[F](kinesisConfig),
      KinesisResources.dynamo[F](dynamoConfig),
      KinesisResources.cloudWatch[F]()
    ).mapN(KinesisBusConfigurator(KinesisBusConfig(appName, streamName, dynamoTableName), log))

  override def busImplementationName = "Kinesis"
}
