---
title: Event streams
sidebar_label: Event streams
---

# Event streams

An event stream moves one kind of event through a short pipeline. Something happens (a tag changes, a message arrives, or another screen posts an event), the stream optionally filters and reshapes it, then one or more handlers do the work: write a log line, call a URL, run a named query, publish to Kafka, or hand the event to another part of the gateway.

You set this up on **Event Streams** in the gateway admin. You do not need to write the stream as JSON. **Edit as JSON** is there when you want a setting the form does not show, or when you already have a stream from a script or the API.

## When to use it

Use a stream when a change should cause work outside the screen that made the change.

- A tank level should call a webhook when it crosses a line.
- A Kafka topic should land in a named query.
- Another service should POST events to the gateway, and the gateway should fan them out.
- A gateway script should publish an event and a stream should handle it.

For a value you only want to show on an HMI, bind the tag on the view. A stream is for the side effect.

## Before you start

- You need a gateway user who can change gateway configuration.
- A tag source needs the tag path, for example `[default]Area/Tank/Level`. Wildcards are not accepted.
- A Kafka source or handler needs a **Kafka connection** on the same page. If the broker wants a password, store it as a gateway secret and type the reference `secret://your-secret-name`. The page never asks you to paste the password into the stream.
- An HTTP source is called by the other system. The address is `/system/eventstream/` plus the stream name.

## Create a stream

1. Open **Event Streams**.
2. Click **New event stream**.
3. Type a short lowercase name. Letters, digits, and hyphens only, such as `tank-level`.
4. Leave **Enabled** checked.
5. Choose a **Source**.
   - **Event listener** — something already on the gateway publishes the event (a script, or **Send test event** on this page).
   - **Tag** — one path per line. Choose whether value, quality, and timestamp changes count, and whether the first sample after save is skipped.
   - **HTTP** — another system POSTs the event. Turn on **Require sign-in** and list roles when the caller must be a gateway user. Turn on **Require HTTPS** when plain HTTP must be rejected.
   - **Kafka** — pick the connection, one topic per line, and where to start if the group has no saved offset (**Latest**, **Earliest**, or **None**).
6. Leave the filter blank to keep every event. A filter expression such as `{event.data.value} > 10` keeps the event only when it is true. A transform expression such as `{event.data.value} * 2` replaces the payload.
7. Set the buffer when events arrive in bursts. **Quiet period** waits for a pause before the handlers run. **Maximum wait** sends the batch even if events keep arriving. **Queue limit** is how many events may wait; **When the queue is full** drops either the oldest or the newest.
8. The first handler is a logger. **Handler message** can be `{event.data}` or a field such as `{event.data.n}`. Add another handler for a tag write, a named query, an HTTP call, a gateway event, a script, or Kafka.
9. Click **Save stream**. The row shows whether the stream is valid. An error under the name is the compiler's reason.

## Try it

With the stream open, **Send test event** posts the JSON in the box through the saved handlers. It works for every source type, including a tag or Kafka source, so you can try the handlers without waiting for the plant.

**Received** counts events the stream accepted. **Filtered out** counts events the filter dropped. **Handler runs** and **Handler errors** tell you whether the handlers finished. **Source connected** is yes for a listener that compiled, and for a Kafka or tag source that is attached.

A disabled stream does not accept the test event. Turn **Enabled** back on and save first.

## Kafka connections

A connection is the broker address, shared by every stream that names it.

1. Under **Kafka connections**, click **New Kafka connection**.
2. Name it, for example `plant`.
3. **Bootstrap servers** is the host and port, such as `broker:9092`. Several servers are separated by commas.
4. Leave the protocol on `PLAINTEXT` for a broker that does not use TLS or a username. `SSL` is TLS without a username. `SASL_PLAINTEXT` and `SASL_SSL` need a username and a password secret reference.
5. Save. Streams then pick this name in **Kafka connection**.

## Expressions and scripts

`{event.data}` is the payload. `{event.data.value}` is a field inside it. `{event.metadata.source}` is who produced the event (`tag`, `http`, `kafka`, `listener`, or `test`).

A named-query handler lists parameters one per line: `n={event.data.n}`. An HTTP handler can do the same for headers: `Content-Type=application/json`.

Scripts are optional. A filter or transform script defines `onEventReceived(event, state)`. The filter returns true to keep the event. The transform returns the new payload. A script handler defines `onEventsReceived(events, state)`. An error script defines `onError(event, error, state)`. `event` has `data` and `metadata`. `state` is remembered for the next call of that stage.

## Edit as JSON

**Edit as JSON** shows the stored stream. Use it for settings the form does not list: handler retry, a Kafka partition, or extra client properties. Saving from the form keeps those extra settings. Saving from the JSON view replaces the stream with the text in the box.

The same stream can still be changed through the configuration API or a gateway script. The screen and the API write the same record.

## Settings

| Setting | What it does |
|---|---|
| Enabled | Off means the stream stays saved but drops new events. |
| Encoder | How a payload is turned into bytes for a handler that sends bytes. JSON, text, or raw bytes. |
| Source | Tag, HTTP, Kafka, or event listener. |
| Filter | Keeps an event when the expression is true, or when the script returns true. Blank keeps every event. |
| Transform | Replaces the payload. Blank leaves the payload as it arrived. |
| Quiet period | Milliseconds of silence before a batch is handled. 0 handles each event on its own. |
| Maximum wait | Longest a batch may wait, even while events keep arriving. |
| Queue limit | How many events may wait. 0 means no limit. |
| When the queue is full | Drop the oldest waiting event, or reject the new one. |
| Handler | Logger, tag write, named query, HTTP, gateway event, script, or Kafka. |
| Error script | Runs when a handler aborts the event. |

## Example

A listener named `tank-level` logs the payload and ignores small values:

- Source: Event listener
- Filter expression: `{event.data.n} > 10`
- Handler type: Logger
- Handler message: `tank {event.data.n}`

Save, then send `{"n": 4}`. **Filtered out** becomes 1 and the handler does not run. Send `{"n": 12}`. **Handler runs** becomes 1.

To let another system post the same event, change the source to HTTP, turn on **Require sign-in**, and set roles to the role that system uses. That system POSTs the JSON to `/system/eventstream/tank-level`.

## Related API

The screen writes a configuration resource: module `events`, kind `EventStream`. Kafka connections are module `kafka`, kind `KafkaConnection`.

| Call | Who can use it | What it does |
|---|---|---|
| `GET /api/v1/event-streams` | Gateway read | Lists streams and counters. |
| `GET /api/v1/event-streams/{name}` | Gateway read | One stream's counters and last error. |
| `POST /api/v1/event-streams/{name}/test` | Gateway write | The **Send test event** button. One JSON body, any enabled stream. |
| `POST /api/v1/event-streams/{name}/listen` | Gateway write | Publishes to a listener source. |
| `POST /system/eventstream/{name}` | Public, or the stream's roles | Publishes to an HTTP source. |
| `system.eventstream.publish` | Gateway script | Publishes to a listener source from a script. |
| `system.eventstream.getStreams` and `system.eventstream.diagnose` | Gateway script | The same counters this page shows. |

## If something looks wrong

- **Valid** is no. Read the error on the row. The usual causes are a missing tag path, a missing Kafka connection or topic, or a handler field left blank.
- **Send test event** is missing. The stream is disabled, or it did not compile. Fix the error, enable it, and save.
- The test returns "too many requests". The queue is full and the stream is set to drop the newest event. Raise **Queue limit**, or wait until the handlers catch up.
- A Kafka source stays disconnected. Check the bootstrap servers, the protocol, and that the password is `secret://...` for a secret that exists.
- HTTP callers get "not permitted". **Require sign-in** is on and the caller's role is not in **Roles**.
- A retry count or Kafka partition disappeared. Those fields stay when you save the form. They are removed only when you delete them in **Edit as JSON** and save that text.
