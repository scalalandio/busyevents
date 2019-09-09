package io.scalaland.busyevents

final case class PublisherError[E](events: List[E], cause: Throwable)
    extends Exception("Event publishing failed", cause)
