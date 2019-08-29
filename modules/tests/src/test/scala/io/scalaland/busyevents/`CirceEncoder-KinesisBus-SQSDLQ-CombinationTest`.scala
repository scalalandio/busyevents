package io.scalaland.busyevents

final class `CirceEncoder-KinesisBus-SQSDLQ-CombinationTest`
    extends EventBusSpecification
    with circe.CirceTestProvider
    with kinesis.KinesisBusTestProvider
    with sqs.SQSDLQTestProvider
