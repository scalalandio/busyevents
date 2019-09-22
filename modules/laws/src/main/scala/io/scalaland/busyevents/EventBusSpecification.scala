package io.scalaland.busyevents

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import cats.data.Nested
import cats.effect._
import cats.implicits.{ catsSyntaxEq => _, _ }
import com.typesafe.scalalogging.Logger
import org.specs2.mutable.Specification
import org.specs2.specification.{ AfterEach, BeforeAfterAll }

import scala.concurrent.ExecutionContext

import scala.concurrent.{ ExecutionContextExecutor, Future }
import scala.concurrent.duration._

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

  // setup and teardown

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
          case ex: Throwable => IO(log.error("Error during suite teardown", ex)) *> ().pure[IO]
        }
        .unsafeRunSync()
    )

  override def after: Unit =
    (busMarkAllAsProcessed[IO] *> dlqMarkAllAsProcessed[IO])
      .flatTap(_ => IO(log.info("Cleanup successful")))
      .recoverWith {
        case ex: Throwable => IO(log.error("Error during after-test cleanup", ex)) *> ().pure[IO]
      }
      .unsafeRunSync()

  // utilities

  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)

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

  val safeToSend: List[Event] = knownSafeToSend.getOrElse(events.take(10).toList) // scalastyle:ignore

  def fetchAllUnprocessedFromBusAsEvents: List[Event] = {
    println("try calculating bus")
    val a = Nested(busFetchNotProcessedDirectly[IO]).map(busExtractor andThen decoder).value.unsafeRunSync().collect {
      case EventDecodingResult.Success(value) => value
    }
    println(s"bus size ${a.size}")
    a
  }

  def publishEventsToBus(events: List[Event]): Unit =
    busPublishDirectly[IO](events.map(encoder).map(busEnveloper)).unsafeRunSync()

  def fetchAllUnprocessedFromDLQAsEvents: List[Event] = {
    println("try calculating dlq")
    val a =
      Nested(dlqFetchTopNotProcessedDirectly[IO]).map(dlqExtractor andThen decoder).value.unsafeRunSync().collect {
        case EventDecodingResult.Success(value) => value
      }
    println(s"bus size ${a.size}")
    a
  }

  def raceAttempts: Int            = 90
  def raceInterval: FiniteDuration = 1.second

  // tests

  s"$codecImplementationName encoder with $busImplementationName bus with $dlqImplementationName DLQ" should {

    "provide Publisher that sends all events in batch or none" in {
      // too many events fail
      knownSafeToSend.map(_.length).foreach { safeSize =>
        // given
        val unsafeEvents = events.take(safeSize * 2).toList

        // when
        log.info("Before sending unsafe events")
        publisher.publishEvents(unsafeEvents).unsafeRunSync() must throwA[Throwable]
        log.info("After sending unsafe events")

        // then
        log.info("Before checking if events failed to publish")
        fetchAllUnprocessedFromBusAsEvents must beEmpty[List[Event]].eventually(raceAttempts, raceInterval)
        log.info("After checking if events failed to publish")
      }

      // "sane" amount succeed

      // when
      log.info("Before sending safe events")
      publisher.publishEvents(safeToSend).unsafeRunSync()
      log.info("After sending safe events")

      // then
      log.info("Before checking if events succeeded to publish")
      fetchAllUnprocessedFromBusAsEvents must ===(safeToSend).eventually(raceAttempts, raceInterval)
      log.info("After checking if events succeeded to publish")
      1 === 1 // required by stupid evidence
    }

    "provide Subscriber that skips over events ignored by processor PartialFunction" in {
      // given
      publishEventsToBus(safeToSend)
      fetchAllUnprocessedFromBusAsEvents must not(beEmpty[List[Event]]).eventually(raceAttempts, raceInterval)

      // when
      log.info("Before starting consuming events (ignore all)")
      val killSwitch = consumer.start[IO](PartialFunction.empty).value._2 // ignore all
      log.info("After starting consuming events (ignore all)")

      // then
      try {
        log.info("Before checking if all events are consumed and none is on DLQ (ignore all)")
        fetchAllUnprocessedFromBusAsEvents must beEmpty[List[Event]].eventually(raceAttempts, raceInterval)
        fetchAllUnprocessedFromDLQAsEvents must beEmpty[List[Event]].eventually(raceAttempts, raceInterval)
        log.info("After checking if all events are consumed and none is on DLQ (ignore all)")
      } finally {
        killSwitch.shutdown()
        log.info("After shutting down consumer (ignore all)")
      }
      1 === 1 // required by stupid evidence
    }

    /*
    "provide Subscriber that marks successfully processed events without any other action" in {
      // given
      @SuppressWarnings(Array("org.wartremover.warts.MutableDataStructures"))
      val processed = scala.collection.mutable.MutableList.empty[Event]
      publishEventsToBus(safeToSend)
      fetchAllUnprocessedFromBusAsEvents must not(beEmpty[List[Event]]).eventually(raceAttempts, raceInterval)

      // when
      log.info("Before starting consuming events (consume all)")
      val killSwitch = consumer.start[IO] { case event => IO(processed += event) }.value._2 // consume all
      log.info("After starting consuming events (consume all)")

      // then
      try {
        log.info("Before checking if all events are consumed and none is on DLQ (consume all)")
        fetchAllUnprocessedFromBusAsEvents must beEmpty[List[Event]].eventually(raceAttempts, raceInterval)
        processed.toSet must beEqualTo(safeToSend.toSet).eventually(10, 500.millis)
        fetchAllUnprocessedFromDLQAsEvents must beEmpty[List[Event]].eventually(raceAttempts, raceInterval)
        log.info("After checking if all events are consumed and none is on DLQ (consume all)")
      } finally {
        killSwitch.shutdown()
        log.info("After shutting down consumer (consume all)")
      }
      1 === 1 // required by stupid evidence
    }

    // TODO: add logs to the rest of tests

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
        fetchAllUnprocessedFromDLQAsEvents must beEqualTo(safeToSend).eventually(raceAttempts, raceInterval)
      } finally {
        killSwitch.shutdown()
      }
    }

    "provide Repairer that attempts to rerun event from dead-letter queue" in {
      // given
      @SuppressWarnings(Array("org.wartremover.warts.MutableDataStructures"))
      val processed = scala.collection.mutable.MutableList.empty[Event]
      publishEventsToBus(safeToSend)

      // when
      val killSwitch1 = consumer
        .start[IO] {
          case _ => IO.raiseError(new Exception("Fail")) // fail all events
        }
        .value
        ._2
      try {
        fetchAllUnprocessedFromDLQAsEvents must beEqualTo(safeToSend).eventually(raceAttempts, raceInterval)
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
        fetchAllUnprocessedFromDLQAsEvents must beEmpty[List[Event]].eventually(raceAttempts, raceInterval)
        processed.toSet === safeToSend.toSet
      } finally {
        killSwitch2.shutdown()
      }
    }
   */
  }
}
