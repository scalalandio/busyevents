package io.scalaland.busyevents

object DockerServices {

  private def serviceAt(port: Int): String = s"http://0.0.0.0:$port/"

  // scalastyle:off
  // keep in sync with docker-compose.yml
  val kinesis:  String = serviceAt(4501)
  val dynamoDB: String = serviceAt(4502)
  val sqs:      String = serviceAt(4503)
  // scalastyle:on
}
