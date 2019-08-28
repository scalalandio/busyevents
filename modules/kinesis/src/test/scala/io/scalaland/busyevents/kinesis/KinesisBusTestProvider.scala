package io.scalaland.busyevents
package kinesis

import cats.effect.{ Async, Resource, Sync }
import cats.implicits._
import com.typesafe.scalalogging.Logger

trait KinesisBusTestProvider extends BusTestProvider {

  // TODO: add setup and teardown!!
  // TODO: creation of stream in kinesis
  // TODO: creation of table in dynamo
  // TODO: deletion of stream in kinesis
  // TODO: deletion of table in dynamo

  val log: Logger

  override type BusEnvelope = KinesisEnvelope

  override val busEnveloper: Enveloper[BusEnvelope] = kinesisEnveloper(_ => "same-key")
  override val busExtractor: Extractor[BusEnvelope] = kinesisExtractor

  override def busEnvironment[F[_]:  Async]: Resource[F, Unit] = Resource.pure(())
  override def busConfigurator[F[_]: Sync]: Resource[F, EventBus.BusConfigurator[KinesisEnvelope]] =
    (
      KinesisResources.kinesis[F](),
      KinesisResources.dynamo[F](),
      KinesisResources.cloudWatch[F]()
    ).mapN(KinesisBusConfigurator(KinesisBusConfig("kinesis-test", "kinesis-bus", "kinesis-bus-check"), log))

  override val busImplementationName = "Kinesis"
}
