# AWS Kinesis integration

Implementation uses [Kinesis Stream by 500px](https://github.com/500px/kinesis-stream) underneath
so its configuration options applies.

## Setup

Add to your sbt file:

```scala
libraryDependencies += "io.scalaland" %% "busyevents-kinesis" % busyeventsVersion
```

Then you'll be able to create bus configuration:

```scala
import cats.effect._
import cats.implicits._
import io.scalaland.busyevents.aws._
import io.scalaland.busyevents.aws.kinesis._

val kinesisBusConfigurator: Resource[IO, KinesisBusConfigurator] = (
  AWSResources.kinesis[IO](kinesisConfig),
  AWSResources.dynamo[IO](dynamoConcig),
  AWSResources.cloudWatch[IO](cloudwatchConfig)
).mapN(
  KinesisBusConfigurator(
    KinesisBusConfig(appName, streamName, dynamoTableName),
    log,
    checkpointConfig = CheckpointConfig()
  )
)
```

### IAM setting

You need the right IAM settings in order to be able to publish/consume events
from Kinesis and track checkpoins with DynamoDB. Example settings can be seen
on [Kinesis Streams](https://github.com/500px/kinesis-stream#required-iam-permissions)
page.


### SLF4j and Logback config

If you want to take a look at some of the internals of used libraries you can
add their options to your `resources/logback.xml`, e.g.:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration scan="false" debug="false">
   <!-- your other logback configs -->

   <!-- AWS error logging -->
  <logger name="com.amazonaws" level="off" />
  <logger name="software.amazon" level="off" />
  <logger name="software.amazon.kinesis.coordinator.Scheduler" level="info" />

  <!-- Kinesis Stream logging -->
  <logger name="px.kinesis.stream.consumer.checkpoint.CheckpointTrackerActor" level="info" />
  <logger name="px.kinesis.stream.consumer.checkpoint.ShardCheckpointTrackerActor" level="info" />
</configuration>
```

Remember to setup logging via slf4j by Akka in `application.conf`:

```hocon
akka {
  loggers = ["akka.event.slf4j.Slf4jLogger"]
  loglevel = "DEBUG"
  logging-filter = "akka.event.slf4j.Slf4jLoggingFilter"
}
```
