package io.scalaland.busyevents

final class `KinesisBus-SQSDLQ-CirceEncoder-CombinationTest`
    extends EventBusSpecification
    with circe.CirceTestProvider
    with kinesis.KinesisBusTestProvider
    with sqs.SQSDLQTestProvider
