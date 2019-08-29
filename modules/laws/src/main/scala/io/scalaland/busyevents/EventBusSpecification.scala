package io.scalaland.busyevents

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import cats.effect.{ IO, Timer }
import cats.implicits.{ catsSyntaxEq => _, _ }
import com.typesafe.scalalogging.Logger
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAfterAll

import scala.concurrent.ExecutionContextExecutor

@SuppressWarnings(Array("org.wartremover.warts.Null"))
trait EventBusSpecification extends Specification with BeforeAfterAll with TestProvider {
  self: CodecTestProvider with BusTestProvider with DLQTestProvider =>

  def implementationName: String =
    s"${codecImplementationName}Encoder-${busImplementationName}Bus-${dlqImplementationName}DLQ"

  override def log: Logger = Logger(s"event-bus.$implementationName")

  // I haven't found easier and "better" way to do this
  // scalastyle:off
  var publisher: Publisher[IO, BusEnvelope, Event] =
    new Publisher[IO, BusEnvelope, Event](_ => ().pure[IO])
  var consumer: Consumer[BusEnvelope, Event] =
    new Consumer[BusEnvelope, Event](null, null, null, null, null)(null, null, null, null)
  var repairer: EventRepairer[DLQEnvelope, Event] =
    new EventRepairer[DLQEnvelope, Event](null, null, null, null, null)(null, null, null, null)
  var teardown: Option[IO[Unit]] = None
  // scalastyle:on

  private def implementationResource =
    busEnvironment[IO] *> dlqEnvironment[IO] *> (for {
      eventBus <- (busConfigurator[IO], dlqConfigurator[IO])
        .mapN(EventBus[Event, BusEnvelope, DLQEnvelope](StreamConfig(implementationName, 1), log))
      systemMaterializerContext <- EventBusResources.implicitDeps[IO]()
    } yield {
      implicit val system:       ActorSystem              = systemMaterializerContext._1
      implicit val materializer: ActorMaterializer        = systemMaterializerContext._2
      implicit val context:      ExecutionContextExecutor = systemMaterializerContext._3
      implicit val timer:        Timer[IO]                = IO.timer(context)

      publisher = eventBus.publisher[IO]
      consumer  = eventBus.consumer
      repairer  = eventBus.repairer
    })

  override def beforeAll(): Unit = teardown = Some(implementationResource.allocated.unsafeRunSync()._2)

  override def afterAll(): Unit = teardown.foreach(_.unsafeRunSync())

  // TODO: implement tests

  s"$codecImplementationName encoder with $busImplementationName bus with $dlqImplementationName DLQ" should {

    "provide Publisher that sends all events in batch or none" in {
      1 === 1 // temporarily
    }

    "provide Subscriber that skips over events ignored by processor PartialFunction" in {
      1 === 1 // temporarily
    }

    "provide Subscriber that marks successfully processed events without any other action" in {
      1 === 1 // temporarily
    }

    "provide Subscriber that pushes failed events to dead-letter queue" in {
      1 === 1 // temporarily
    }

    "provide Repairer that attempts to rerun event from dead-letter queue" in { 1 === 1 }
  }
}
