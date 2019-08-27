package io.scalaland.busyevents
package kinesis

import cats.effect.SyncIO
import cats.implicits._
import com.typesafe.scalalogging.Logger

trait KinesisBusTestProvider extends BusTestProvider with ResourcesSpec {

  val log: Logger

  override type BusEnvelope = KinesisEnvelope
  override lazy val busConfigurator: EventBus.BusConfigurator[KinesisEnvelope] = useResource {
    // TODO: set up configs
    val kinesisBusConfig: KinesisBusConfig = null
    (
      AWSClientResources.kinesis[SyncIO](),
      AWSClientResources.dynamo[SyncIO](),
      AWSClientResources.cloudWatch[SyncIO]()
    ).mapN(KinesisBusConfigurator(kinesisBusConfig, log))
  }
  override val busImplementation = "Kinesis"
}
