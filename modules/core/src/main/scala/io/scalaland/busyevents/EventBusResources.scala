package io.scalaland.busyevents

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import cats.effect.{ Async, Resource, Sync }
import cats.implicits._
import io.scalaland.busyevents.utils.FutureToAsync

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.ExecutionContextExecutor

object EventBusResources {

  def actorSystem[F[_]: Async](create: => ActorSystem = ActorSystem()): Resource[F, ActorSystem] =
    Resource.make {
      Sync[F].delay(create)
    } { system =>
      Sync[F].defer(system.terminate().asAsync[F].void)
    }

  def implicitDeps[F[_]: Async](
    create: => ActorSystem = ActorSystem()
  ): Resource[F, (ActorSystem, ActorMaterializer, ExecutionContextExecutor)] =
    actorSystem[F](create).map { implicit system =>
      (system, ActorMaterializer(), system.dispatcher)
    }
}
