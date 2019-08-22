import sbt._
import Settings._

lazy val root = project.root
  .setName("busyevents")
  .setDescription("BusyEvents build script")
  .configureRoot
  .aggregate(core, laws, circe, tests)

lazy val core = project.from("core")
  .setName("busyevents-core")
  .setDescription("Simple event bus interface")
  .setInitialImport()
  .configureModule
  .settings(Compile / resourceGenerators += task[Seq[File]] {
    val file = (Compile / resourceManaged).value / "busyevents-version.conf"
    IO.write(file, s"version=${version.value}")
    Seq(file)
  })

lazy val laws = project.from("laws")
  .setName("busyevents-laws")
  .setDescription("Contracts that all combination of codec-bus-dql should hold")
  .setInitialImport()
  .configureModule
  .settings(
    libraryDependencies += Dependencies.spec2Core
  )
  .dependsOn(core)

lazy val circe = project.from("circe")
  .setName("busyevents-circe")
  .setDescription("Turn Circe codecs into busyevent codecs")
  .setInitialImport("io.scalaland.busyevents.circe._")
  .configureModule
  .configureTests()
  .settings(
    libraryDependencies += Dependencies.circe,
    libraryDependencies += Dependencies.circeParser,
    libraryDependencies += Dependencies.circeGeneric % Test
  )
  .dependsOn(core, laws % "test->compile")

lazy val tests = project.from("tests")
  .setName("tests")
  .setDescription("Tests of modules")
  .setInitialImport()
  .configureModule
  .configureTests()
  .settings(
    libraryDependencies += Dependencies.logback
  )
  .dependsOn(core, laws % "test->compile")
  .compileAndTestDependsOn(circe)

addCommandAlias("fullTest", ";test;scalastyle")
addCommandAlias("fullCoverageTest", ";coverage;test;coverageReport;coverageAggregate;scalastyle")
addCommandAlias("relock", ";unlock;reload;update;lock")
