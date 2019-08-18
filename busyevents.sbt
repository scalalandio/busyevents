import sbt._
import Settings._

lazy val root = project.root
  .setName("busyevents")
  .setDescription("BusyEvents build script")
  .configureRoot
  .aggregate(common, first, second)

lazy val common = project.from("common")
  .setName("common")
  .setDescription("Common utilities")
  .setInitialImport()
  .configureModule
  .configureTests()
  .configureFunctionalTests()
  .configureIntegrationTests()
  .settings(Compile / resourceGenerators += task[Seq[File]] {
    val file = (Compile / resourceManaged).value / "busyevents-version.conf"
    IO.write(file, s"version=${version.value}")
    Seq(file)
  })

lazy val first = project.from("first")
  .setName("first")
  .setDescription("First project")
  .setInitialImport("io.scalaland.busyevents.first._")
  .configureModule
  .configureTests()
  .compileAndTestDependsOn(common)
  .configureRun("io.scalaland.busyevents.first.First")

lazy val second = project.from("second")
  .setName("second")
  .setDescription("Second project")
  .setInitialImport("io.scalaland.busyevents.second._")
  .configureModule
  .configureTests()
  .compileAndTestDependsOn(common)
  .configureRun("io.scalaland.busyevents.second.Second")

addCommandAlias("fullTest", ";test;fun:test;it:test;scalastyle")
addCommandAlias("fullCoverageTest", ";coverage;test;fun:test;it:test;coverageReport;coverageAggregate;scalastyle")
addCommandAlias("relock", ";unlock;reload;update;lock")
