package io.scalaland.busyevents
package aws
package kinesis

import cats.effect.{ Async, Resource, Sync }
import cats.implicits._
import io.scalaland.busyevents.utils.FutureToAsync
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.model.{ CreateTableRequest, DeleteTableRequest }
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.kinesis.model.{
  CreateStreamRequest,
  DeleteStreamRequest,
  GetRecordsRequest,
  GetShardIteratorRequest,
  PutRecordsRequest,
  PutRecordsRequestEntry,
  Record,
  ShardIteratorType
}
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.JavaConverters._
import scala.compat.java8.FutureConverters._

trait KinesisBusTestProvider extends BusTestProvider with AWSTestProvider {

  /// name for specification descriptions
  override def busImplementationName = "Kinesis"

  private def kinesisEndpoint = DockerServices.kinesis
  private def dynamoEndpoint  = DockerServices.dynamoDB
  private def appName         = s"kinesis-test-$providerId"
  private def streamName      = s"kinesis-bus-$providerId"
  private def dynamoTableName = s"kinesis-bus-check-$providerId"
  private def kinesisConfig: ClientConfig[KinesisAsyncClient]  = testConfig(kinesisEndpoint)
  private def dynamoConfig:  ClientConfig[DynamoDbAsyncClient] = testConfig(dynamoEndpoint)

  /// type used in tests
  override type BusEnvelope = KinesisEnvelope

  // implementations

  override def busEnveloper: Enveloper[BusEnvelope] = kinesisEnveloper(_ => "same-key")
  override def busExtractor: Extractor[BusEnvelope] = kinesisExtractor

  private var client: KinesisAsyncClient = _
  override def busEnvironment[F[_]: Async]: Resource[F, Unit] = {
    val kinesisStream = AWSResources
      .kinesis[F](kinesisConfig)
      .map { kinesis =>
        client = kinesis
        kinesis
      }
      .flatMap { kinesis =>
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
    val dynamoTable = AWSResources.dynamo[F](dynamoConfig).flatMap { dynamo =>
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
      AWSResources.kinesis[F](kinesisConfig),
      AWSResources.dynamo[F](dynamoConfig),
      AWSResources.cloudWatch[F]()
    ).mapN(KinesisBusConfigurator(KinesisBusConfig(appName, streamName, dynamoTableName), log))

  // test utilities

  override def isSafeForPublishing(msgSizes: Seq[Long]): Boolean =
    (msgSizes.length <= 500L) && msgSizes.forall(_ <= (1024L * 1024L)) && (msgSizes.sum <= (5L * 1024L * 1024L))
  override def busPublishDirectly[F[_]: Async](envelope: List[BusEnvelope]): F[Unit] = Async[F].defer {
    client
      .putRecords(
        PutRecordsRequest
          .builder()
          .streamName(streamName)
          .records(
            envelope.map { e =>
              PutRecordsRequestEntry.builder().partitionKey(e.key).data(SdkBytes.fromByteBuffer(e.byteBuffer)).build()
            }.asJavaCollection
          )
          .build()
      )
      .toScala
      .asAsync[F]
      .void
  }
  override def busFetchNotProcessedDirectly[F[_]: Async](): F[List[BusEnvelope]] = Async[F].defer {
    for {

      firstShardId <- "shardId-000000000000".pure[F]
      // apparently it doesn't work with kinesalite
      //shards <- client.listShards(ListShardsRequest.builder().streamName(streamName).build()).toScala.asAsync[F]
//      firstShardId <- if (shards.shards().isEmpty) Async[F].raiseError[String](new Exception("No shards!"))
//      else shards.shards().get(0).shardId().pure[F]
      iterator <- client
        .getShardIterator(
          GetShardIteratorRequest
            .builder()
            .streamName(streamName)
            .shardId(firstShardId)
            .shardIteratorType(ShardIteratorType.TRIM_HORIZON)
            .build()
        )
        .toScala
        .asAsync[F]
      records <- (List.empty[Record] -> Option(iterator.shardIterator())).tailRecM[F, List[Record]] {
        case (list, Some(it)) =>
          client.getRecords(GetRecordsRequest.builder().shardIterator(it).limit(1000).build()).toScala.asAsync[F].map {
            response =>
              val nextRecords = response.records.asScala.toList
              ((list ++ nextRecords) -> Option(response.nextShardIterator()).filter(_ => nextRecords.nonEmpty))
                .asLeft[List[Record]]
          }
        case (list, _) => list.asRight[(List[Record], Option[String])].pure[F]
      }
    } yield records.map(r => KinesisEnvelope(r.partitionKey(), r.data().asByteBuffer(), None))
  }
  override def busMarkAllAsProcessed[F[_]: Async]: F[Unit] = busFetchNotProcessedDirectly[F]().void
}
