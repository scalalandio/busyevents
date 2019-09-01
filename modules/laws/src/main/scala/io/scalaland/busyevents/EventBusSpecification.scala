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
  var publisher: Publisher[IO, BusEnvelope, Event] = _
  var consumer:  Consumer[BusEnvelope, Event]      = _
  var repairer:  EventRepairer[DLQEnvelope, Event] = _
  var teardown:  Option[IO[Unit]]                  = None
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
      // TODO: quota of events sent at once
      val publishableEvents = events(10) // scalastyle:ignore
      publisher.publishEvents(publishableEvents).unsafeRunSync()
      // TODO: action for fetching all unprocessed events from stream without committing them

      1 === 1 // temporarily
    }

    "provide Subscriber that skips over events ignored by processor PartialFunction" in {
      // TODO: action for publishing events directly

      // TODO: action for fetching all unprocessed events from stream without committing them
      1 === 1 // temporarily
    }

    "provide Subscriber that marks successfully processed events without any other action" in {
      // TODO: action for publishing events directly

      // TODO: action for fetching all unprocessed events from stream without committing them
      1 === 1 // temporarily
    }

    "provide Subscriber that pushes failed events to dead-letter queue" in {
      // TODO: action for publishing events directly

      // TODO: action for fetching all unprocessed events from stream without committing them

      // TODO: action for fetching all unprocessed events from queue without deleting them
      1 === 1 // temporarily
    }

    "provide Repairer that attempts to rerun event from dead-letter queue" in {
      // TODO: action for publishing events to queue directly

      // TODO: action for fetching all unprocessed events from queue without deleting them
      1 === 1
    }
  }
}
