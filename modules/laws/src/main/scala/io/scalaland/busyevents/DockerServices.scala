package io.scalaland.busyevents

object DockerServices {

  private def serviceAt(port: Int): String = s"http://0.0.0.0:$port/"

  // scalastyle:off
  // keep in sync with docker-compose.yml
  val kinesis:  String = serviceAt(4568)
  val dynamoDB: String = serviceAt(4569)
  val sqs:      String = serviceAt(4576)
  // scalastyle:on
}
