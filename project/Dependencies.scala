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
  val catsVersion       = "1.6.0"
  val catsEffectVersion = "1.4.0"
  val circeVersion      = "0.11.1"
  val monixVersion      = "3.0.0-RC3"
  val specs2Version     = "4.6.0"

  // resolvers
  val resolvers = Seq(
    Resolver sonatypeRepo "public",
    Resolver typesafeRepo "releases"
  )

  // akka
  val akkaStream         = "com.typesafe.akka"            %% "akka-stream"                 % akkaVersion
  val alpakkaKafka       = "com.typesafe.akka"            %% "akka-stream-kafka"           % "1.0.5"
  val kinesisStreams     = "com.500px"                    %% "kinesis-stream"              % "0.1.6"
  val alpakkaSQS         = "com.lightbend.akka"           %% "akka-stream-alpakka-sqs"     % "1.1.1"
  // clients
  val kinesisClient      = "software.amazon.kinesis"      %  "amazon-kinesis-client"       % "2.2.2"
  // functional libraries
  val cats               = "org.typelevel"                %% "cats-core"                   % catsVersion
  val catsEffect         = "org.typelevel"                %% "cats-effect"                 % catsEffectVersion
  val shapeless          = "com.chuusai"                  %% "shapeless"                   % "2.3.3"
  // async
  val monixExecution     = "io.monix"                     %% "monix-execution"             % monixVersion
  val monixEval          = "io.monix"                     %% "monix-eval"                  % monixVersion
  // config
  val scopt              = "com.github.scopt"             %% "scopt"                       % "3.7.1"
  val scalaConfig        = "com.typesafe"                 %  "config"                      % "1.3.4"
  val pureConfig         = "com.github.pureconfig"        %% "pureconfig"                  % "0.11.0"
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

  val mainDeps = Seq(akkaStream, cats, catsEffect, scalaLogging)

  val testDeps = Seq(spec2Core, spec2Scalacheck)

  implicit final class ProjectRoot(project: Project) {

    def root: Project = project in file(".")
  }

  implicit final class ProjectFrom(project: Project) {

    private val commonDir = "modules"

    def from(dir: String): Project = project in file(s"$commonDir/$dir")
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
