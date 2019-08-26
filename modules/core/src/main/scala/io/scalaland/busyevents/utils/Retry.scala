package io.scalaland.busyevents.utils

import akka.actor.ActorSystem
import cats.implicits._
import cats.effect.{ Sync, Timer }

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.Future

class Retry(maxRetries: Int, minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double) {

  private case class State(attempt: Int = 0, delay: FiniteDuration = minBackoff) {
    def shouldRetry: Boolean = attempt < maxRetries
    def nextRetry: State =
      copy(attempt + 1, ((delay * randomFactor) match {
        case fd: FiniteDuration => fd
        case _ => maxBackoff
      }) min maxBackoff)
  }

  def apply[A](
    thunk:       => Future[A]
  )(beforeRetry: Throwable => Unit)(beforeAbort: Throwable => Unit)(implicit system: ActorSystem): Future[A] = {
    import system.dispatcher
    State().tailRecM[Future, A] { retry =>
      thunk.map(_.asRight[State]).recoverWith[Either[State, A]] {
        case error if retry.shouldRetry =>
          akka.pattern.after(retry.delay, system.scheduler)(
            Future.successful {
              beforeRetry(error)
              retry.nextRetry.asLeft[A]
            }
          )
        case error =>
          Future.failed {
            beforeAbort(error)
            error
          }
      }
    }
  }

  def apply[F[_]: Sync: Timer, A](thunk: F[A])(beforeRetry: Throwable => Unit)(beforeAbort: Throwable => Unit): F[A] =
    State().tailRecM[F, A] { retry =>
      thunk.map(_.asRight[State]).recoverWith {
        case error if retry.shouldRetry =>
          Timer[F].sleep(retry.delay) *> Sync[F].delay {
            beforeRetry(error)
            retry.nextRetry.asLeft[A]
          }
        case error =>
          Sync[F].defer {
            beforeAbort(error)
            Sync[F].raiseError(error)
          }
      }
    }
}

object Retry {

  def apply(maxRetries: Int, minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double): Retry =
    new Retry(maxRetries, minBackoff, maxBackoff, randomFactor)

  def apply(retryConfig: RetryConfig): Retry =
    apply(retryConfig.maxRetries, retryConfig.minBackoff, retryConfig.maxBackoff, retryConfig.randomFactor)
}
