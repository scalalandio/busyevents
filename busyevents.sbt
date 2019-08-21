import sbt._
import Settings._

lazy val root = project.root
  .setName("busyevents")
  .setDescription("BusyEvents build script")
  .configureRoot
  .aggregate(core)

lazy val core = project.from("core")
  .setName("core")
  .setDescription("Simple event bus interface")
  .setInitialImport()
  .configureModule
  .configureTests()
  .settings(Compile / resourceGenerators += task[Seq[File]] {
    val file = (Compile / resourceManaged).value / "busyevents-version.conf"
    IO.write(file, s"version=${version.value}")
    Seq(file)
  })

addCommandAlias("fullTest", ";test;scalastyle")
addCommandAlias("fullCoverageTest", ";coverage;test;coverageReport;coverageAggregate;scalastyle")
addCommandAlias("relock", ";unlock;reload;update;lock")
