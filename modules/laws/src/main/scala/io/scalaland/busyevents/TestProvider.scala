package io.scalaland.busyevents

import java.util.UUID

import cats.effect.{ Bracket, Resource, Sync }
import cats.implicits._
import com.typesafe.scalalogging.Logger

trait TestProvider {

  val providerId: UUID = UUID.randomUUID()

  def log: Logger

  protected def loggedResource[F[_]: Sync, A](
    resourceName: String
  )(acquire:      => F[A])(release: A => F[Unit]): Resource[F, A] =
    Resource.make {
      (Sync[F].delay(log.debug(s"Acquiring resource: $resourceName")) *> Sync[F].defer(acquire) <*
        Sync[F].delay(log.debug(s"Acquired resource: $resourceName"))).handleErrorWith { error =>
        Sync[F].delay(log.error(s"Failed to acquire resource $resourceName", error)) *> error.raiseError[F, A]
      }
    } { res =>
      (Sync[F].delay(log.debug(s"Releasing resource: $resourceName")) *>
        Sync[F].defer(release(res)) <*
        Sync[F].delay(log.debug(s"Released resource: $resourceName"))).handleErrorWith { error =>
        Sync[F].delay(log.error(s"Failed to release resource $resourceName", error))
      }
    }

  protected def loggedResourceFrom[F[_]: Sync: Bracket[?[_], Throwable], A](
    resourceName: String
  )(resource:     Resource[F, A]): Resource[F, A] = Resource {
    (Sync[F].delay(log.debug(s"Acquiring resource: $resourceName")) *>
      resource.allocated.map {
        case (a, release) =>
          a -> (Sync[F].delay(log.debug(s"Releasing resource: $resourceName")) *> release <*
            Sync[F].delay(log.debug(s"Released resource: $resourceName"))).handleErrorWith { error =>
            Sync[F].delay(log.error(s"Failed to release resource $resourceName", error))
          }
      } <* Sync[F].delay(log.debug(s"Acquired resource: $resourceName"))).handleErrorWith { error =>
      Sync[F].delay(log.error(s"Failed to acquire resource $resourceName", error)) *> error.raiseError[F, (A, F[Unit])]
    }
  }
}
