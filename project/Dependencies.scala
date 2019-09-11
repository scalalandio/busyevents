import sbt._

import Dependencies._

object Dependencies {

  // scala version
  val scalaOrganization = "org.scala-lang"
  val scalaVersion      = "2.12.9"

  // build tools version
  val scalaFmtVersion = "1.5.1"

  // libraries versions
  val akkaVersion       = "2.5.25"
  val awsSdkVersion     = "2.5.65"
  val catsVersion       = "2.0.0"
  val catsEffectVersion = "2.0.0"
  val circeVersion      = "0.11.1"
  val specs2Version     = "4.6.0"

  // resolvers
  val resolvers = Seq(
    Resolver sonatypeRepo "public",
    Resolver typesafeRepo "releases"
  )

  // akka
  val akkaSlf4j          = "com.typesafe.akka"            %% "akka-slf4j"                  % akkaVersion
  val akkaStream         = "com.typesafe.akka"            %% "akka-stream"                 % akkaVersion
  val alpakkaKafka       = "com.typesafe.akka"            %% "akka-stream-kafka"           % "1.0.5"
  val kinesisStreams     = "com.500px"                    %% "kinesis-stream"              % "0.1.7"
  val alpakkaSQS         = "com.lightbend.akka"           %% "akka-stream-alpakka-sqs"     % "1.1.1"
  // clients
  val awsSDKCore         = "software.amazon.awssdk"       %  "aws-core"                    % awsSdkVersion
  val awsNioClient       = "software.amazon.awssdk"       %  "netty-nio-client"            % awsSdkVersion
  val kinesisClient      = "software.amazon.kinesis"      %  "amazon-kinesis-client"       % "2.2.2"
  // functional libraries
  val cats               = "org.typelevel"                %% "cats-core"                   % catsVersion
  val catsEffect         = "org.typelevel"                %% "cats-effect"                 % catsEffectVersion
  // serialization
  val circe              = "io.circe"                     %% "circe-core"                  % circeVersion
  val circeGeneric       = "io.circe"                     %% "circe-generic"               % circeVersion
  val circeParser        = "io.circe"                     %% "circe-parser"                % circeVersion
  // logging
  val scalaLogging       = "com.typesafe.scala-logging"   %% "scala-logging"               % "3.9.2"
  val logback            = "ch.qos.logback"               %  "logback-classic"             % "1.2.3"
  // testing
  val spec2Core          = "org.specs2"                   %% "specs2-core"                 % specs2Version
  val spec2Scalacheck    = "org.specs2"                   %% "specs2-scalacheck"           % specs2Version
}

trait Dependencies {

  val scalaOrganizationUsed = scalaOrganization
  val scalaVersionUsed = scalaVersion

  val scalaFmtVersionUsed = scalaFmtVersion

  // resolvers
  val commonResolvers = resolvers

  val mainDeps = Seq(akkaSlf4j, akkaStream, cats, catsEffect, scalaLogging)

  val testDeps = Seq(spec2Core, spec2Scalacheck)

  implicit final class ProjectRoot(project: Project) {

    def root: Project = project in file(".")
  }

  implicit final class ProjectFrom(project: Project) {

    private val commonDir = "modules"

    private val integrationDir = "integrations"

    def from(dir: String): Project = project in file(s"$commonDir/$dir")

    def integration(dir: String): Project = project in file(s"$integrationDir/$dir")
  }

  implicit final class DependsOnProject(project: Project) {

    private val testConfigurations = Set("test", "fun", "it")
    private def findCompileAndTestConfigs(p: Project) =
      (p.configurations.map(_.name).toSet intersect testConfigurations) + "compile"

    private val thisProjectsConfigs = findCompileAndTestConfigs(project)
    private def generateDepsForProject(p: Project) =
      p % (thisProjectsConfigs intersect findCompileAndTestConfigs(p) map (c => s"$c->$c") mkString ";")

    def compileAndTestDependsOn(projects: Project*): Project =
      project dependsOn (projects.map(generateDepsForProject): _*)
  }
}
