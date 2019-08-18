package io.scalaland.busyevents.second

import com.typesafe.scalalogging.Logger
import pureconfig._
import pureconfig.generic.auto._

object Second {

  val config = loadConfig[SecondConfig]("second").getOrElse(SecondConfig("undefined"))

  val logger = Logger(getClass)

  def main(args: Array[String]): Unit = logger.info(s"Run first at version: ${config.version}")
}
