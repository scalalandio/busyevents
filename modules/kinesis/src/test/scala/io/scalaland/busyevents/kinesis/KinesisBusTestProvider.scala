package io.scalaland.busyevents
package kinesis

import java.net.URI

import cats.effect.{ Async, Resource, Sync }
import cats.implicits._
import io.scalaland.busyevents.kinesis.KinesisResources.ClientConfig
import software.amazon.awssdk.services.dynamodb.model.{ CreateTableRequest, DeleteTableRequest }
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.kinesis.model.{ CreateStreamRequest, DeleteStreamRequest }
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient

trait KinesisBusTestProvider extends BusTestProvider {

  private val kinesisEndpoint = "http://0.0.0.0:4501/"
  private val dynamoEndpoint  = "http://0.0.0.0:4502/"
  private val appName         = s"kinesis-test-$providerId"
  private val streamName      = s"kinesis-bus-$providerId"
  private val dynamoTableName = s"kinesis-bus-check-$providerId"
  private val kinesisConfig: ClientConfig[KinesisAsyncClient] =
    ClientConfig().copy(endpointOverride = Some(URI.create(kinesisEndpoint)))
  private val dynamoConfig: ClientConfig[DynamoDbAsyncClient] =
    ClientConfig().copy(endpointOverride = Some(URI.create(dynamoEndpoint)))

  override type BusEnvelope = KinesisEnvelope

  override val busEnveloper: Enveloper[BusEnvelope] = kinesisEnveloper(_ => "same-key")
  override val busExtractor: Extractor[BusEnvelope] = kinesisExtractor

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

  override val busImplementationName = "Kinesis"
}
