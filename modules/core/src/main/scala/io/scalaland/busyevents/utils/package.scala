package io.scalaland.busyevents

import cats.effect.Async

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

package object utils {

  implicit class FutureToAsync[A](val future: Future[A]) extends AnyVal {

    def asAsync[F[_]: Async](implicit ec: ExecutionContext): F[A] = Async[F].async { callback =>
      future.onComplete {
        case Success(value) => callback(Right(value))
        case Failure(error) => callback(Left(error))
      }
    }
  }
}
