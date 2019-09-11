import sbt._
import Settings._

lazy val root = project.root
  .setName("busyevents")
  .setDescription("BusyEvents build script")
  .configureRoot
  .aggregate(core, laws, jsonCirce, awsCommon, awsKinesis, awsSQS, tests)

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

lazy val migration = project.from("migration")
  .setName("busyevents-migration")
  .setDescription("Utility for migration from old bus to new bus")
  .setInitialImport()
  .configureModule
  .dependsOn(core)

lazy val laws = project.from("laws")
  .setName("busyevents-laws")
  .setDescription("Contracts that all combination of codec-bus-dql should hold")
  .setInitialImport()
  .configureModule
  .settings(
    libraryDependencies += Dependencies.spec2Core,
    libraryDependencies += Dependencies.spec2Scalacheck
  )
  .dependsOn(core, migration)

// encoders integrations

lazy val jsonCirce = project.integration("json-circe")
  .setName("busyevents-circe")
  .setDescription("Turn Circe codecs into busyevents codecs")
  .setInitialImport("io.scalaland.busyevents.circe._")
  .configureModule
  .configureTests()
  .settings(
    libraryDependencies += Dependencies.circe,
    libraryDependencies += Dependencies.circeParser,
    libraryDependencies += Dependencies.circeGeneric % Test
  )
  .dependsOn(core, laws % "test->compile")

// AWS integrations

lazy val awsCommon = project.integration("aws-common")
  .setName("busyevents-aws-common")
  .setDescription("Common AWs configs of all AWS integrations")
  .setInitialImport("io.scalaland.busyevents.aws._")
  .configureModule
  .configureTests()
  .settings(
    libraryDependencies += Dependencies.awsSDKCore,
    libraryDependencies += Dependencies.awsNioClient
  )
  .dependsOn(core, laws % "test->compile")

lazy val awsKinesis = project.integration("aws-kinesis")
  .setName("busyevents-kinesis")
  .setDescription("Use AWS Kinesis as event bus")
  .setInitialImport("io.scalaland.busyevents.aws.kinesis._")
  .configureModule
  .configureTests()
  .settings(
    libraryDependencies += Dependencies.kinesisClient,
    libraryDependencies += Dependencies.kinesisStreams
  )
  .compileAndTestDependsOn(awsCommon)

lazy val awsSQS = project.integration("aws-sqs")
  .setName("busyevents-sqs")
  .setDescription("Use AWS SQS as dead letter queue")
  .setInitialImport("io.scalaland.busyevents.aws.sqs._")
  .configureModule
  .configureTests()
  .settings(
    libraryDependencies += Dependencies.alpakkaSQS
  )
  .compileAndTestDependsOn(awsCommon)

// tests

lazy val tests = project.from("tests")
  .setName("tests")
  .setDescription("Tests of modules")
  .setInitialImport()
  .configureModule
  .configureTests(requiresFork = true)
  .settings(
    libraryDependencies += Dependencies.logback
  )
  .dependsOn(core, laws % "test->compile")
  .compileAndTestDependsOn(jsonCirce, awsKinesis, awsSQS)

addCommandAlias("fullTest", ";test;scalastyle")
addCommandAlias("fullCoverageTest", ";coverage;test;coverageReport;coverageAggregate;scalastyle")
addCommandAlias("relock", ";unlock;reload;update;lock")
