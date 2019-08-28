package io.scalaland.busyevents

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import cats.effect.{ IO, Timer }
import cats.implicits._
import com.typesafe.scalalogging.Logger
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAfterAll

import scala.concurrent.ExecutionContextExecutor

@SuppressWarnings(Array("org.wartremover.warts.Null"))
trait EventBusSpecification extends Specification with BeforeAfterAll {
  self: CodecTestProvider with BusTestProvider with DLQTestProvider =>

  val implementationName: String = s"$codecImplementationName-$busImplementationName-$dlqImplementationName"

  val log: Logger = Logger(s"event-bus.$implementationName")

  // I haven't found easier and "better" way to do this
  var publisher: Publisher[IO, BusEnvelope, Event] = _
  var consumer:  Consumer[BusEnvelope, Event]      = _
  var repairer:  EventRepairer[DLQEnvelope, Event] = _
  var teardown:  Option[IO[Unit]]                  = None

  private val implementationResource = busEnvironment[IO] *> dlqEnvironment[IO] *> (for {
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
}
