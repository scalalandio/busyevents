package io.scalaland.busyevents

import cats.effect.{ Resource, SyncIO }
import org.specs2.data.AlwaysTag
import org.specs2.specification.core.{ Fragments, SpecificationStructure }
import org.specs2.specification.create.FragmentsFactory

import scala.collection.mutable

trait ResourcesSpec extends SpecificationStructure with FragmentsFactory {

  @SuppressWarnings(Array("org.wartremover.warts.MutableDataStructures"))
  private val queue: mutable.Queue[SyncIO[Unit]] = mutable.Queue.empty

  def useResource[A](resource: Resource[SyncIO, A]): A = {
    val (value, free) = resource.allocated.unsafeRunSync()
    queue.enqueue(free)
    value
  }

  private def freeAll(): Unit = queue.toList.foreach(_.redeem(_ => (), identity).unsafeRunSync())

  override def map(fs: => Fragments): Fragments =
    super.map(fs).append(Seq(fragmentFactory.step(freeAll()), fragmentFactory.markAs(AlwaysTag)))
}
