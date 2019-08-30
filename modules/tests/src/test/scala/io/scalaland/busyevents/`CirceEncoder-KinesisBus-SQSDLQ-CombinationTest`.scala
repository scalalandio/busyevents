package io.scalaland.busyevents

final class `CirceEncoder-KinesisBus-SQSDLQ-CombinationTest`
    extends EventBusSpecification
    with circe.CirceTestProvider
    with aws.kinesis.KinesisBusTestProvider
    with aws.sqs.SQSDLQTestProvider
