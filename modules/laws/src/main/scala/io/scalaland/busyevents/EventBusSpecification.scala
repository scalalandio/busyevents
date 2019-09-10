package io.scalaland.busyevents

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import cats.data.Nested
import cats.effect.{ IO, Timer }
import cats.implicits.{ catsSyntaxEq => _, _ }
import com.typesafe.scalalogging.Logger
import org.specs2.mutable.Specification
import org.specs2.specification.{ AfterEach, BeforeAfterAll }

//import scala.collection.mutable
import scala.concurrent.{ ExecutionContextExecutor, Future }

@SuppressWarnings(Array("org.wartremover.warts.Null"))
trait EventBusSpecification extends Specification with BeforeAfterAll with AfterEach with TestProvider {
  self: CodecTestProvider with BusTestProvider with DLQTestProvider =>

  sequential

  // initialization

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

  override def beforeAll(): Unit =
    teardown = Some(
      implementationResource.allocated
        .flatTap(_ => IO(log.info("Setup successful")))
        .recoverWith {
          case ex: Throwable =>
            IO(log.error("Error during suite setup", ex)) *>
              IO.raiseError(new Exception("Error during suite setup", ex))
        }
        .unsafeRunSync()
        ._2
    )

  override def afterAll(): Unit =
    teardown.foreach(
      _.flatTap(_ => IO(log.info("Teardown successful")))
        .recoverWith {
          case ex: Throwable =>
            IO(log.error("Error during suite teardown", ex)) *>
              IO.raiseError(new Exception("Error during suite teardown", ex))
        }
        .unsafeRunSync()
    )

  override def after: Any =
    (busMarkAllAsProcessed[IO] *> dlqMarkAllAsProcessed[IO])
      .flatTap(_ => IO(log.info("Cleanup successful")))
      .recoverWith {
        case ex: Throwable =>
          IO(log.error("Error during after-test cleanup", ex)) *>
            IO.raiseError(new Exception("Error during after-test cleanup", ex))
      }
      .unsafeRunSync()

  // utilities

  implicit val runToFuture: RunToFuture[IO] = new RunToFuture[IO] {
    override def apply[A](fa: IO[A]): Future[A] = fa.unsafeToFuture()
  }

  lazy val knownSafeToSend: Option[List[Event]] = {
    val cache      = events
    val sizedCache = cache.map(eventSizeInBytes)
    def isNSafe(tested: Int) = isSafeForPublishing(sizedCache.take(tested))

    @scala.annotation.tailrec
    def findUpperBound(tested: Int): Option[Int] =
      if (tested < 100000) None // w need some limit to know if there is a point in searching max safe value at all
      else if (isNSafe(tested)) findUpperBound(tested * 2)
      else Some(tested)

    // lower is known to be safe, upper is known to not necessarily be safe
    @scala.annotation.tailrec
    def searchN(lowerBound: Int, upperBound: Int): Int = {
      val middle = (lowerBound + upperBound) / 2
      if (lowerBound + 1 >= upperBound) lowerBound
      else if (isNSafe(middle)) searchN(middle, upperBound)
      else searchN(lowerBound, middle)
    }

    findUpperBound(1).map(searchN(1, _)).map(cache.take(_).toList)
  }
  val safeToSend: List[Event] = knownSafeToSend.getOrElse(events.take(100).toList) // scalastyle:ignore

  def fetchAllUnprocessedFromBusAsEvents: List[Event] =
    Nested(busFetchNotProcessedDirectly[IO]()).map(busExtractor andThen decoder).value.unsafeRunSync().collect {
      case EventDecodingResult.Success(value) => value
    }
  def publishEventsToBus(events: List[Event]): Unit =
    busPublishDirectly[IO](events.map(encoder).map(busEnveloper)).unsafeRunSync()

  def fetchAllUnprocessedFromDLQAsEvents: List[Event] =
    Nested(dlqFetchTopNotProcessedDirectly[IO]()).map(dlqExtractor andThen decoder).value.unsafeRunSync().collect {
      case EventDecodingResult.Success(value) => value
    }

  // tests

  s"$codecImplementationName encoder with $busImplementationName bus with $dlqImplementationName DLQ" should {

    "provide Publisher that sends all events in batch or none" in {
      // too many events fail
      knownSafeToSend.map(_.length).foreach { safeSize =>
        // given
        val unsafeEvents = events.take(safeSize * 2).toList

        // when
        publisher.publishEvents(unsafeEvents).unsafeRunSync() must throwA[Throwable]

        // then
        fetchAllUnprocessedFromBusAsEvents must beEmpty[List[Event]].eventually
      }

      // "sane" amount succeed

      // when
      publisher.publishEvents(safeToSend).unsafeRunSync()

      // then
      fetchAllUnprocessedFromBusAsEvents must ===(safeToSend).eventually
    }

    "provide Subscriber that skips over events ignored by processor PartialFunction" in {
      // given
      publishEventsToBus(safeToSend)
      fetchAllUnprocessedFromBusAsEvents must not(beEmpty[List[Event]]).eventually

      // when
      val killSwitch = consumer.start[IO](PartialFunction.empty).value._2 // ignore all

      // then
      try {
        fetchAllUnprocessedFromBusAsEvents must beEmpty[List[Event]].eventually
        fetchAllUnprocessedFromDLQAsEvents must beEmpty[List[Event]].eventually
      } finally {
        killSwitch.shutdown()
      }
    }

    /*
    "provide Subscriber that marks successfully processed events without any other action" in {
      // given
      @SuppressWarnings(Array("org.wartremover.warts.MutableDataStructures"))
      val processed = mutable.MutableList.empty[Event]
      publishEventsToBus(safeToSend)
      fetchAllUnprocessedFromBusAsEvents must not(beEmpty)

      // when
      val killSwitch = consumer
        .start[IO] {
          case event => IO(processed += event)
        }
        .value
        ._2

      // then
      try {
        fetchAllUnprocessedFromBusAsEvents must beEmpty[List[Event]].eventually
        processed.toSet === safeToSend.toSet
        fetchAllUnprocessedFromDLQAsEvents must beEmpty
      } finally {
        killSwitch.shutdown()
      }
    }

    "provide Subscriber that pushes failed events to dead-letter queue" in {
      // given
      publishEventsToBus(safeToSend)

      // when
      val killSwitch = consumer
        .start[IO] {
          case _ => IO.raiseError(new Exception("Fail")) // fail all events
        }
        .value
        ._2

      // then
      try {
        fetchAllUnprocessedFromDLQAsEvents must beEqualTo(safeToSend).eventually
      } finally {
        killSwitch.shutdown()
      }
    }

    "provide Repairer that attempts to rerun event from dead-letter queue" in {
      // given
      @SuppressWarnings(Array("org.wartremover.warts.MutableDataStructures"))
      val processed = mutable.MutableList.empty[Event]
      publishEventsToBus(safeToSend)

      // when
      val killSwitch1 = consumer
        .start[IO] {
          case _ => IO.raiseError(new Exception("Fail")) // fail all events
        }
        .value
        ._2
      try {
        fetchAllUnprocessedFromDLQAsEvents must beEqualTo(safeToSend).eventually
      } finally {
        killSwitch1.shutdown()
      }
      val killSwitch2 = repairer
        .start[IO] {
          case event => IO(processed += event)
        }
        .value
        ._2

      // then
      try {
        fetchAllUnprocessedFromDLQAsEvents must beEmpty[List[Event]].eventually
        processed.toSet === safeToSend.toSet
      } finally {
        killSwitch2.shutdown()
      }
    }
   */
  }
}
