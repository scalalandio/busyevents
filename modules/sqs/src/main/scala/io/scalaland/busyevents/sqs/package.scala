package io.scalaland.busyevents

import software.amazon.awssdk.services.sqs.model.Message

package object sqs {

  implicit val sqsExtractor: Extractor[Message] = (message: Message) => RawEvent.wrap(message.body().getBytes)
}
