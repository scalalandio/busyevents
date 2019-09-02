# BusyEvents

Work in progress - if you can help/are interested ping me.

The goal is to provide some common reliable interface of an event bus to provide implementations for.

Development of an universal solution that fits everyone is a non-goal.

A goal is to provide a safe, sane, minimal implementation that could be used out of the box
by teams that don't have capacities to learn about and handle many intrinsic details (that
would appear "obvious" only after things would fail on a production environment).

## General idea

The interface is split into:

 * codecs - formats and functions for encoding to/decoding from `ByteBuffer`,
 * bus - implementations enveloping/extracting `ByteBuffer` into some native format,
   publishing events and providing Reactive Streams flows for consuming/committing
   consumed events
 * dead-letter queue - implementation of a queue that would store events that failed
   to process: enveloper/extractor of `ByteBuffer`, Reactive Stream flows
   for publishing/consuming events that failed in the original run. 
   
This allows having general implementation, where platform-specific details will be
provided separately for each platform as a configuration.

Event bus makes some assumptions about the flow, to keep things simple:

 * publisher should allow publishing events in batches - each batch should be
   transactional, either all events are published or none is,
 * consumer takes `PartialFunction[Event, F[Unit]]`, where `F: Async`:
   * it consumes events from the bus but doesn't mark them as processed
     until the partial function gets evaluated for them,
   * not matched events are treated as skipped and marked as processed,
   * matched events that evaluate into successful `F[Unit]` are treated
     as successful and marked as processed,
   * matched events that throw `Throwable` are marked as processed but
     ONLY AFTER they were successfully put on a dead-letter queue,
 * repairer takes `PartialFunction[Event, F[Unit]]`, where `F: Async`:
   * it consumes events from dead-letter queue, that were enqueued before
     repair was run, and doesn't dequeue them until they are marked as processed,
   * not matched events are treated as skipped and marked as processed,
   * matched events that evaluate into successful `F[Unit]` are treated
     as successful and marked as processed,
   * matched events that throw `Throwable` are marked as processed but
     ONLY AFTER they were successfully rescheduled on dead-letter queue.

If one needs some customization it is suggested to achieve it using configuration
and/or tweaking function passed into consumer/repairer:

 * if you don't want to use dead-letter queue, an empty DLQ will be provided later
   (though you should use it at your own risk),
 * retries are not handled by the implementation - since it takes `PartialFunction[Event, F[Unit]]`
   you are free to implement any sort of blocking/retrying logic there.

## Usage

TODO once tests are written and most important TODOs are addressed

## Implementations

 * codecs
   * JSON using Circe
 * buses
   * AWS Kinesis
 * dead-letter queues
   * AWS SQS

TODO: once things works/are published add some code here
