package io.scalaland

import java.nio.ByteBuffer

import akka.NotUsed
import akka.stream.scaladsl.{ Flow, Source }
import cats.~>

import scala.concurrent.Future

package object busyevents {

  type RunToFuture[F[_]] = F ~> Future

  type RawEvent = java.nio.ByteBuffer
  object RawEvent {
    def copiedFrom(size: Int, copy: ByteBuffer => Any): RawEvent = {
      val buffer = ByteBuffer.allocate(size)
      copy(buffer)
      buffer.flip()
      buffer
    }
    def wrap(bytes: Array[Byte]): RawEvent = ByteBuffer.wrap(bytes)
  }

  private[busyevents] implicit class FlowWithContext[In, Out, Mat](val flow: Flow[In, Out, Mat])
    extends AnyVal {

    def withContext[Ctx]: Flow[(In, Ctx), (Out, Ctx), NotUsed] = Flow[(In, Ctx)].flatMapConcat {
      case (in, ctx) =>
        Source[In](List[In](in)).via(flow).map { _ -> ctx }
    }
  }
}
