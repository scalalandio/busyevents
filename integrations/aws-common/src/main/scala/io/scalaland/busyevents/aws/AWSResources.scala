package io.scalaland.busyevents
package aws

import cats.effect.{ Resource, Sync }
import cats.implicits._
import software.amazon.awssdk.core.SdkClient

object AWSResources {

  private[aws] def resource[F[_]: Sync, A <: SdkClient](thunk: => A): Resource[F, A] =
    Resource.fromAutoCloseable[F, A](
      Sync[F].delay(thunk).recoverWith {
        case ex: Throwable => new Exception("Error during AWS client initialization", ex).raiseError[F, A]
      }
    )
}
