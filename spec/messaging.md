# Gecko Messaging Specification

Version 2.0 draft — API packages `org.gecko.messaging.api*` version 1.0.0
(`MessagingConstants.MESSAGING_SPECIFICATION_VERSION = "1.0.0"`).

Status: specification draft, written spec-first against the reference implementation in this
workspace. Requirements are those of OSGi RFP 0192 (Messaging); RFC 0246 was the earlier draft.
Where this document and the code of the reference implementation disagree, the code was taken
and the difference is listed in [17 Open Issues](#17-open-issues).

The key words MUST, MUST NOT, SHOULD, SHOULD NOT and MAY are to be interpreted as described in
RFC 2119.

---

## 1 Introduction

Applications talk to many messaging systems: MQTT brokers on the edge, AMQP brokers in the data
centre, WebSocket hubs towards browsers. Each comes with its own client library, threading model,
reconnect logic and vocabulary. This specification defines one asynchronous, protocol-neutral API
for sending messages to channels and receiving them from channels, and a way for an
implementation to say exactly what it can do.

The API lives in `org.gecko.messaging.api` and its sub-packages. It is designed so that it can
become an OSGi specification later: service properties and capabilities already use the
`osgi.messaging` namespace.

### 1.1 Essentials

- **Protocol neutral** — one API for MQTT 5, MQTT 3.1.1, AMQP 0-9-1, WebSocket and an in-JVM
  broker; application code does not import a client library.
- **Asynchronous** — publishing returns an OSGi `Promise`; subscribing returns an OSGi
  `PushStream`.
- **Features announced, never assumed** — every instance announces its features in the service
  property `osgi.messaging.feature`; anything not announced fails with
  `FeatureNotSupportedException`.
- **Instances** — a messaging instance is one configuration (one connection) exposed as a set of
  services with common properties; many instances may coexist.
- **Connection owned by the instance** — reconnects with exponential backoff and jitter;
  subscriptions are restored before the instance reports `CONNECTED`.
- **Request/response** — reply-to on the requesting side (`ReplyToPublisher`) and as a whiteboard
  on the answering side.
- **Acknowledgement** — filter, function or manual acknowledgement; reject with re-queue (queues
  only) at most once.
- **Introspection** — runtime DTOs and a connection listener whiteboard.
- **CloudEvents** — optional binary or structured content mode per instance.
- **Secure by default** — bounded resources, restricted reply channels, no credentials in
  service properties, hardened envelope parsing.
- **TCK** — one protocol-neutral TCK that tests every announced feature and that every feature not
  announced fails.

### 1.2 Entities

| Entity | Description |
|---|---|
| Messaging instance | One configuration of a protocol binding: a connection, its subscriptions and its services. Identified by `osgi.messaging.name`. |
| Channel | The address of messages: topic, queue or exchange, depending on protocol. |
| Channel extension | A second address part, e.g. an AMQP routing key. |
| `Message` | Immutable payload (`ByteBuffer`) plus `MessageContext`. |
| `MessageContext` | Immutable metadata: channel, content type, correlation, reply-to, headers, extensions. |
| `MessageContextBuilder` | Prototype-scoped service building contexts and messages; adaptable to specialised builders. |
| `MessagePublisher` | Publishes messages asynchronously. |
| `MessageSubscription` | Subscribes to channels, returns `PushStream<Message>`. |
| `ReplyToPublisher` | Sends a request and receives one or many responses. |
| `ReplyToSingleSubscriptionHandler`, `ReplyToManySubscriptionHandler` | Whiteboard services answering requests. |
| `MessageServiceRuntime` | Introspection: `MessagingRuntimeDTO`, `ConnectionState`. |
| `ConnectionListener` | Whiteboard service receiving `ConnectionEvent`s. |
| Feature | A named capability of an instance (`FEATURE_*` in `MessagingConstants`). |
| Extension | A key of `MessageContext.getExtensions()` that asks for an option, e.g. QoS. |
| Protocol binding | A bundle implementing the specification for one protocol. |

### 1.3 Synopsis

A protocol binding registers a Declarative Services component per factory configuration. Each
component creates one messaging instance, which registers its services with the instance
properties and starts connecting. A client selects an instance by filter, e.g.
`(osgi.messaging.name=sensors)`, and checks `osgi.messaging.feature` before it relies on a
feature.

To send, the client obtains a builder (prototype scope, or the builder handed to
`MessagePublisher.publish(Function)`), builds a `Message` with channel, payload and options, and
publishes it. The returned promise resolves when the message is handed over, or fails.

To receive, the client calls `MessageSubscription.subscribe` and connects a terminal operation to
the stream. The instance subscribes on the broker, delivers messages through a bounded buffer and
restores the subscription after every reconnect. Closing the stream ends the subscription.

For request/response, the client calls `ReplyToPublisher.publishWithReply`; an answering party
registers a reply-to handler whose `osgi.messaging.target` selects the instance.

---

## 2 Messaging Instance

### 2.1 Lifecycle

1. A configuration is created for the factory PID of a binding (§13). The configuration MUST
   contain `osgi.messaging.name`; otherwise construction fails with `MessagingException`.
2. The instance validates its configuration (unknown `cloudEvents` mode, missing `uri` for
   network bindings, unsupported binding options) and fails activation on errors. With
   `cloudEvents=binary` on a protocol without headers, start fails with a `MessagingException`
   naming `cloudEvents=structured` as the alternative.
3. `start` registers the services (§2.3), opens the `ConnectionListener` tracker and, if
   `replyToSubscription` is announced, the reply-to whiteboard, then schedules the first connect
   attempt immediately.
4. `stop` (deactivation) closes the whiteboard, fires `CLOSED`, unregisters the services, fails
   waiting reply-to exchanges, closes every subscription stream (normal close event), disconnects
   and releases all threads.

Threads: one scheduler thread (connect attempts, timeouts) and at most `workerThreads` delivery
threads per instance, which time out after 30 s idle. An implementation MUST NOT run protocol
driver reconnects in parallel to its own (§10).

### 2.2 Configuration

Common keys, read by `MessagingConfiguration` and `AbstractMessagingInstance`. Protocol keys are
listed per binding in §13. Numeric values may be given as numbers or strings.

| Key | Type | Default | Meaning |
|---|---|---|---|
| `osgi.messaging.name` | String | required | Instance name, unique in the framework. |
| `uri` | String | binding specific | Connection URI. Required for `mqtt`, `mqtt3`, `amqp`, `websocket`. |
| `username` | String | — | User name. |
| `.password` / `password` | String | — | Password; `.password` (private property) is read first. |
| `clientId` | String | instance name | Stable client identity on the broker. |
| `connectTimeout` | long ms | `10000` | Deadline of one connect attempt, including handshake; also of an MQTT subscribe and a WebSocket channel session. |
| `reconnectDelay` | long ms | `1000` | First backoff of the reconnect cycle (at least 10). |
| `reconnectMaxDelay` | long ms | `30000` | Largest backoff (at least 10); also how long a connection must hold before the backoff starts over. |
| `replyTimeout` | long ms | `30000` | Default wait of a `ReplyToPublisher` for a response. |
| `replyChannelPrefix` | String | `osgi/messaging/reply/<clientId>` | Prefix of generated reply channels. |
| `replyToAllowed` | String (comma-separated), String[] or Collection | `osgi/messaging/reply/` and, if set, `replyChannelPrefix` + `/` | Prefixes a reply-to channel of a received request must start with to be answered; `*` allows any (§9.6). A configured prefix matches as given: end it with the separator to allow a subtree. |
| `cloudEvents` | String | `none` | `none`, `binary` or `structured` (case-insensitive) (§11). |
| `cloudEvents.acceptPlain` | Boolean | `false` | With CloudEvents, also deliver received messages that are no CloudEvent, unchanged (§11.1). |
| `cloudEvents.source` | String | `/osgi/messaging/<name>` | `source` of plain messages sent as CloudEvents. |
| `cloudEvents.type` | String | `org.gecko.messaging.message` | `type` of plain messages sent as CloudEvents. |
| `workerThreads` | int | `4` (min 1) | Delivery and handler threads. |
| `bufferSize` | int | `1024` (min 1) | Buffer of each subscription stream; in-flight limit of each reply-to handler. |
| `queuePolicy` | String | `BLOCK` (all bindings) | Policy of a full subscription buffer: `BLOCK`, `DISCARD_OLDEST`, `FAIL`. |
| `context.channel` | String | — | Default channel for `subscribe(null)` and publishing without channel. |
| `context.channelExtension` | String | — | Default channel extension (with `context.channel`). |
| `context.contentType` | String | — | Default content type of the default context. |
| `context.contentEncoding` | String | — | Default content encoding of the default context. |
| `context.extension.<key>` | any | — | Extension entry `<key>` of the default context. |

The default context is used when `subscribe(MessageContext)` gets `null`, or when a published
message has no context; a context without channel takes the configured channel. With neither, the
operation fails with `MessagingException("No channel given and none configured")`.

### 2.3 Services and Service Properties

Every instance MUST register:

| Service | Condition | Scope |
|---|---|---|
| `MessagePublisher` | always | singleton |
| `MessageSubscription` | always | singleton |
| `MessageContextBuilder` | always | prototype (`PrototypeServiceFactory`) |
| `MessageServiceRuntime` | always | singleton |
| `ReplyToPublisher` | feature `replyTo` announced | singleton |

All services of an instance carry the same properties:

| Property | Constant | Type | Value |
|---|---|---|---|
| `osgi.messaging.name` | `MESSAGING_NAME_PROPERTY` | String | Instance name. |
| `osgi.messaging.provider` | `MESSAGING_PROVIDER_PROPERTY` | String | `gecko`. |
| `osgi.messaging.protocol` | `MESSAGING_PROTOCOL_PROPERTY` | String[] | Protocol name, e.g. `{"mqtt"}`. |
| `osgi.messaging.feature` | `MESSAGING_FEATURE_PROPERTY` | String[] | Announced features (§3). |
| `osgi.messaging.maxMessageSize` | `MESSAGING_MAX_MESSAGE_SIZE_PROPERTY` | Long | Largest payload in bytes; only if the instance limits it. |
| `uri` | — | String | The URI **without user info**. |
| every other configuration key | — | as configured | Except keys starting with `.`, `component.`, `service.`, `objectClass`, null values and secret keys (§14.4). |

A consumer MUST NOT assume a feature is present without checking `osgi.messaging.feature`, e.g.
`(&(osgi.messaging.name=sensors)(osgi.messaging.feature=acknowledge))`.

### 2.4 Capabilities

A binding bundle provides (annotation `ProvideMessagingImplementation` in core):

- `osgi.implementation;osgi.implementation=osgi.messaging;version:Version=1.0.0;osgi.messaging.protocol:List<String>=<protocol>`
- `osgi.service;objectClass:List<String>="org.gecko.messaging.api.MessagePublisher,org.gecko.messaging.api.MessageSubscription,org.gecko.messaging.api.MessageContextBuilder,org.gecko.messaging.api.runtime.MessageServiceRuntime"`

A consumer bundle SHOULD require an implementation with `@RequireMessaging` (package
`org.gecko.messaging.api.annotations`), optionally `@RequireMessaging(protocol = "mqtt")`; it
generates a requirement on `osgi.implementation=osgi.messaging` in the version range of the
specification, filtered by protocol if given.

---

## 3 Features

A feature is a capability an instance announces in `osgi.messaging.feature`. Features are **per
instance**: the configuration may change them (structured CloudEvents adds content type, headers
and reply-to on any protocol, §11). Using a feature that is not announced MUST fail with
`FeatureNotSupportedException` whose `getFeature()` names the feature — before anything is sent
or subscribed. `publish` and `publishWithReply` report it as failed promise; `subscribe` and
`publishWithReplyMany` throw it at once.

| Constant | Value | Meaning | Required by (checked in `Features`) |
|---|---|---|---|
| `FEATURE_PUBLISH_SUBSCRIBE` | `publishSubscribe` | Topics: every subscriber receives. | `osgi.messaging.channelType=topic` |
| `FEATURE_POINT_TO_POINT` | `pointToPoint` | Queues: one receiver per message. | `osgi.messaging.channelType=queue` |
| `FEATURE_WILDCARD` | `wildcard` | Wildcards in subscription channels. | a whole channel segment `+`, `#` or `*` (segments split on `/` and `.`) on subscribe |
| `FEATURE_CHANNEL_EXTENSION` | `channelExtension` | Channel extensions (routing keys). | `getChannelExtension() != null` |
| `FEATURE_CONTENT_TYPE` | `contentType` | Content type and encoding travel. | `contentType` or `contentEncoding` set on publish |
| `FEATURE_HEADERS` | `headers` | Application headers travel. | non-empty headers on publish |
| `FEATURE_QOS` | `qos` | QoS 1 and 2. | `osgi.messaging.qos` other than 0 |
| `FEATURE_TIME_TO_LIVE` | `timeToLive` | Message expiry. | `osgi.messaging.timeToLive` set on publish |
| `FEATURE_RETAIN` | `retain` | Retained messages. | `osgi.messaging.retain=true` on publish |
| `FEATURE_AUTO_CREATE` | `autoCreate` | Channels created on demand. | `osgi.messaging.autoCreate=true` |
| `FEATURE_DURABLE` | `durable` | Channels/subscriptions survive the client. | `osgi.messaging.durable=true` |
| `FEATURE_ACKNOWLEDGE` | `acknowledge` | Manual acknowledgement and rejection. | acknowledge filter/function/manual/post consumers/`requeueOnReject` on subscribe; `adapt(AcknowledgeMessageContextBuilder.class)` |
| `FEATURE_REPLY_TO` | `replyTo` | Reply-to with a single response; correlation id and reply-to travel. | `correlationId` or `replyTo` set on publish; `ReplyToPublisher` registered |
| `FEATURE_REPLY_TO_MANY` | `replyToMany` | Reply-to with a stream of responses. | `publishWithReplyMany`; binding of a `ReplyToManySubscriptionHandler` |
| `FEATURE_REPLY_TO_SUBSCRIPTION` | `replyToSubscription` | The reply-to whiteboard. | whiteboard opened |
| `FEATURE_GENERATE_CORRELATION_ID` | `generateCorrelationId` | Missing correlation id of a request is generated. | request without correlation id |
| `FEATURE_GENERATE_REPLY_CHANNEL` | `generateReplyChannel` | Missing reply channel of a request is generated. | request without reply-to |
| `FEATURE_CLOUD_EVENTS` | `cloudEvents` | Messages are CloudEvents. | a `CloudEventMessageContext` on publish; `adapt(CloudEventMessageContextBuilder.class)` |

Further rules:

- QoS 0 (`QOS_AT_MOST_ONCE`) MUST always be accepted. A QoS value that is not an integer in
  0..2 fails with `FeatureNotSupportedException` for `qos`.
- An unknown value of `osgi.messaging.channelType` fails with `FeatureNotSupportedException`.
  `getFeature()` returns a `FEATURE_*` name or, for a value no feature covers (an unknown channel
  type), the extension key carrying it (`osgi.messaging.channelType`).
- A builder that cannot be adapted fails with the feature name (`acknowledge`, `cloudEvents`).
- A binding MAY refuse in addition what a feature cannot express, through the hook
  `checkContext(MessageContext, boolean publish)`, called after the feature check for every publish
  and subscribe and before anything is sent. Example: AMQP refuses QoS 2 (`qos`) and a queue with
  a channel extension (`channelExtension`) with `FeatureNotSupportedException`.
- Extension keys outside the standard set are implementation specific and are ignored by
  bindings that do not know them.

---

## 4 Channels

### 4.1 Channel Types

The extension `osgi.messaging.channelType` (`EXTENSION_CHANNEL_TYPE`) selects the type; without
it the instance default applies, which for all bindings of this workspace is a topic.

| Constant | Value | Semantics |
|---|---|---|
| `CHANNEL_TYPE_TOPIC` | `topic` | Publish-subscribe: every matching subscription receives each message. |
| `CHANNEL_TYPE_QUEUE` | `queue` | Point-to-point: each message goes to one of the subscriptions of the queue (competing consumers). |

Publisher and subscriber of a queue MUST both use the channel type `queue`. Mapping per binding:

| Binding | Topic | Queue |
|---|---|---|
| memory | MQTT-style topic | broker queue, round robin over all subscriptions of the name |
| mqtt, mqtt3 | MQTT topic | shared subscription `$share/<sharedGroup>/<channel>`, default group `messaging` (all instances compete); locally round robin over subscriptions of the same filter; a queue publish is a plain topic publish, so topic subscribers of the same name receive it too |
| amqp | routing key on the topic exchange (`exchange`, default `amq.topic`), one exclusive server-named queue per subscription | named queue on the default exchange |
| websocket | URL path below the base URI | not supported |

### 4.2 Wildcards

Wildcards are allowed in subscription channels only, on instances announcing `wildcard`. Without
the feature, a subscription whose channel has a whole segment `+`, `#` or `*` MUST fail with
`FeatureNotSupportedException` (`wildcard`), since the protocol would take the symbol literally.

| Binding | Separator | Single level | Multi level |
|---|---|---|---|
| memory | `/` | `+` | `#` |
| mqtt, mqtt3 | `/` | `+` | `#` |
| amqp | `.` | `*` | `#` |
| websocket | — | — | — |

A message received through a wildcard subscription MUST carry the concrete channel it was
published to (MSG260).

### 4.3 Channel Extension

`MessageContextBuilder.channel(name, extension)` sets a channel extension; requires
`channelExtension`. On AMQP the channel names an exchange (type `topic`) and the extension is the
routing key (wildcards allowed on subscribe); a received message carries the exchange as channel
and the concrete routing key as extension. On memory a subscription with extension receives only
messages with an equal extension. A queue has no extension (AMQP refuses it).

### 4.4 Auto-Create and Durable

| Extension | Type | Effect |
|---|---|---|
| `osgi.messaging.autoCreate` (`EXTENSION_AUTO_CREATE`) | Boolean | `true`: create the channel if missing. Requires `autoCreate`. |
| `osgi.messaging.durable` (`EXTENSION_DURABLE`) | Boolean | `true`: the channel or subscription survives the client. Requires `durable`. |

On AMQP, `autoCreate=true` declares the queue (on publish and subscribe) or the exchange of a
channel extension, once per connection, with the `durable` flag; `durable=true` also sends
persistent messages (delivery mode 2). Without `autoCreate` AMQP uses existing exchanges/queues
only ("do not auto-create"). MQTT, memory and WebSocket create channels implicitly; `false` has no
effect there. MQTT session persistence is configured with `cleanStart`/`sessionExpiryInterval`
(§13.2), not with `durable`.

---

## 5 Messages and Contexts

### 5.1 Message

`Message.payload()` MUST return a new read-only buffer over the same content, positioned at the
start, on every call; an empty payload is an empty buffer, never `null`. `getContext()` is never
`null`. Messages are immutable; a built message copies the remaining bytes of the given buffer.

### 5.2 MessageContext

| Method | Content | Received from |
|---|---|---|
| `getChannel()` | topic, queue or exchange | the concrete channel received on |
| `getChannelExtension()` | e.g. routing key | AMQP routing key with extension subscriptions |
| `getContentType()`, `getContentEncoding()` | payload description | protocol field / user property / envelope |
| `getCorrelationId()` | assigns response to request | protocol field / envelope |
| `getReplyToChannel()` | where responses go | protocol field / envelope |
| `getHeaders()` | application headers; values `String`, `Number`, `Boolean` | MQTT 5 user properties, AMQP headers, CloudEvents extensions |
| `getExtensions()` | options for the implementation, not transmitted as headers | e.g. `osgi.messaging.qos` of the received message |

Contexts are immutable; all maps are unmodifiable and never `null`. Values a protocol cannot carry
are `null`. A protocol carrying strings only (MQTT user properties) delivers every header value as
`String`. Every context of the implementation is an `AcknowledgeMessageContext`; received
CloudEvents have a `CloudEventMessageContext` (§11).

Standard extension keys (prefix `osgi.messaging.`):

| Constant | Key | Type | Used on |
|---|---|---|---|
| `EXTENSION_QOS` | `osgi.messaging.qos` | Integer 0/1/2 | publish, subscribe, received |
| `EXTENSION_TIME_TO_LIVE` | `osgi.messaging.timeToLive` | Long ms | publish |
| `EXTENSION_RETAIN` | `osgi.messaging.retain` | Boolean | publish, received (MQTT) |
| `EXTENSION_AUTO_CREATE` | `osgi.messaging.autoCreate` | Boolean | publish, subscribe |
| `EXTENSION_DURABLE` | `osgi.messaging.durable` | Boolean | publish, subscribe |
| `EXTENSION_CHANNEL_TYPE` | `osgi.messaging.channelType` | String `topic`/`queue` | publish, subscribe |
| `EXTENSION_REPLY_TIMEOUT` | `osgi.messaging.replyTo.timeout` | Long ms | reply-to request (removed before sending) |

### 5.3 MessageContextBuilder

The builder service MUST be registered with prototype scope; a builder is not thread safe. Obtain
one per user (`@Reference(scope = ReferenceScope.PROTOTYPE_REQUIRED)`) or use
`MessagePublisher.publish(Function)`, which hands over a fresh builder.

- `buildContext()` / `buildMessage()` reset the builder to its initial state.
- `withContext(ctx)` replaces all values with those of `ctx`, including acknowledgement settings
  and CloudEvent attributes; later calls override.
- `content(ByteBuffer)` takes position..limit without changing the buffer;
  `content(T, Function<T,ByteBuffer>)` maps a typed object.
- `header(key, value)` accepts `String`, `Number`, `Boolean`; any other type MUST fail with
  `IllegalArgumentException`; `null` removes. `extensionEntry(key, null)` removes.
- `isAdaptable(type)` / `adapt(type)`: adapting to `AcknowledgeMessageContextBuilder` requires
  `acknowledge`, to `CloudEventMessageContextBuilder` requires `cloudEvents`; otherwise `adapt`
  throws `FeatureNotSupportedException` naming that feature. No other type is adaptable
  (implementation types are not part of the contract). The adapted builder shares the state;
  `messageContextBuilder()` returns to the base builder.

```java
Message m = builder.channel("sensors/t1")
    .contentType("application/json")
    .header("unit", "C")
    .extensionEntry(MessagingConstants.EXTENSION_QOS, MessagingConstants.QOS_AT_LEAST_ONCE)
    .content(ByteBuffer.wrap(json))
    .buildMessage();
```

---

## 6 Publishing

`MessagePublisher` methods:

| Method | Context used |
|---|---|
| `publish(Message)` | the message context (default context if it has none) |
| `publish(Message, String channel)` | the message context with the channel replaced |
| `publish(Message, MessageContext)` | the given context replaces the message context; only the payload is used; `null` → message context |
| `publish(Function<MessageContextBuilder, Message>)` | a fresh builder is handed to the function |

Pipeline, in this order (`AbstractMessagingInstance.publish`); every failure is reported as a
failed promise, never thrown:

1. Resolve the context and the default channel (§2.2).
2. Feature check (§3), then the binding's `checkContext`.
3. Size check: a payload larger than the binding limit fails with `MessagingException`.
4. Connection check: while not `CONNECTED`, publishing MUST fail with `MessagingException`.
   Messages are not buffered for a reconnect.
5. CloudEvents encoding if configured (§11), then hand-over to the protocol.

The promise MUST resolve when the message is handed over: with QoS ≥ 1 (MQTT) or publisher
confirms (AMQP, QoS 1) when the broker confirmed it, otherwise when it was written to the
connection. A broker nack fails it.

**Quality of service** (`osgi.messaging.qos`): `0` at most once (send-and-forget), `1` at least
once (send-and-forward, duplicates possible), `2` exactly once. Without the extension the binding
default applies (configuration `qos`, default `1` for MQTT and AMQP).

**Time-to-live** (`osgi.messaging.timeToLive`, ms): MQTT 5 message expiry interval in seconds,
rounded up, at least 1; AMQP `expiration` in ms.

**Retain** (`osgi.messaging.retain=true`): the broker keeps the last message of a topic for later
subscribers; an empty payload clears it (MQTT, memory). Not on queues.

**Size limits**: an instance that limits payloads SHOULD announce the limit in
`osgi.messaging.maxMessageSize` and MUST fail a larger publish before sending (MSG205). MQTT,
MQTT 3.1.1, AMQP and WebSocket limit to `maxMessageSize` (default 1 MiB), sent and received; the
in-memory binding has no limit.

---

## 7 Subscribing

`subscribe(String channel)` subscribes with defaults; `subscribe(MessageContext)` with channel and
options (QoS, channel type, acknowledgement); `null` uses the configured default context. Unlike
publishing, `subscribe` checks the context at once and throws: `FeatureNotSupportedException` for
a feature not announced (wildcards, queues, acknowledgement, ...), `MessagingException` for a
context without channel. What happens later on the broker fails the stream.

Rules:

- The subscription is made when a terminal operation connects to the stream and ends when the
  stream is closed (the broker subscription is removed).
- If the instance is connected, the subscription MUST be in place on the broker when the terminal
  operation returns; messages published afterwards are received. If not connected, it is made
  on the next connect.
- A subscription MUST survive connection losses; the instance restores it after every reconnect
  before it reports `CONNECTED`. Connection changes are not stream events; they go to
  `ConnectionListener`s (§10).
- The stream fails only if the subscription cannot be made or restored (broker refusal, broker
  cancellation, fatal connect failure, a failing consumer); it is closed normally when the
  instance stops.
- Messages of one subscription are delivered in the order received.
- A message the instance cannot decode (e.g. an invalid CloudEvent) is dropped, rejected without
  re-queue and logged without stack trace.

**Buffers and back-pressure**: each stream is built with an `ArrayBlockingQueue` of `bufferSize`,
the queue policy `queuePolicy` and a fixed push-back of 0, on the worker threads of the instance.

| `queuePolicy` | Full buffer |
|---|---|
| `BLOCK` (default) | the delivering protocol thread blocks; the broker's flow control (MQTT `receiveMaximum`, AMQP `prefetch`, TCP) holds back further messages |
| `DISCARD_OLDEST` | the oldest buffered message is dropped |
| `FAIL` | the stream fails |

---

## 8 Acknowledgement

Requires `acknowledge`. A subscription configures it with
`builder.adapt(AcknowledgeMessageContextBuilder.class)`. The modes are decided in this order:

| Mode | Builder method | Behaviour |
|---|---|---|
| Function | `acknowledgeFunction(Consumer<Message>)` | Called before the stream with the message's `AcknowledgeHandler`. If it decides nothing the message is acknowledged; a rejected message does not enter the stream. |
| Filter | `acknowledgeFilter(Predicate<Message>)` | Accepted → acknowledged and streamed; refused → rejected, not streamed. |
| Manual | `manualAcknowledge()` | The consumer calls `acknowledge()`/`reject()` on `((AcknowledgeMessageContext) m.getContext()).getAcknowledgeHandler()`. Messages still open when the stream closes are rejected. |
| Automatic | — | Acknowledged on receipt. |

Further options: `requeueOnReject(boolean)` (re-queue instead of dead-letter/discard),
`postAcknowledge(Consumer)` and `postReject(Consumer)` (called after the decision; failures are
logged).

`AcknowledgeHandler`: only the first `acknowledge()`/`reject()` has an effect and returns `true`;
later calls return `false`. `getAcknowledgeState()` is `RECEIVED`, `ACKNOWLEDGED`, `REJECTED`, or
`UNSUPPORTED` when the message has no acknowledgement.

**Re-queue only on queues, at most once.** `requeueOnReject` takes effect on point-to-point
subscriptions only: on a topic a message published again would reach every subscriber, so a
reject on a topic never re-queues. A message MUST be re-queued at most
`Acknowledger.MAX_REQUEUES = 1` times (fixed, not configurable); a reject with re-queue of a
message that was re-queued before drops it. A function or filter that throws rejects the message
**without** re-queue. A message arriving for an already closed subscription is rejected, with
re-queue only on a queue.

| Binding | Acknowledge | Reject | Re-queue (queues) | "Re-queued before" detected by |
|---|---|---|---|---|
| memory | no-op | drop | route again to the next subscriber of the queue | redelivery counter |
| mqtt | PUBACK/PUBREC (`messageArrivedComplete`, manual acks on) | acknowledge and drop | publish again to the queue topic, acknowledge original | user property `requeued` |
| mqtt3 | as mqtt | as mqtt | as mqtt | fingerprint (topic + payload) in a set of at most 1024 (`REQUEUE_MEMORY`) |
| amqp | `basicAck` | `basicNack` without requeue | `basicNack` with requeue | broker `redelivered` flag (also set after a lost connection) |
| websocket | not supported | — | — | — |

On MQTT and AMQP the protocol acknowledgement is deferred until the decision, so `receiveMaximum`
/ `prefetch` bound the unacknowledged messages.

---

## 9 Reply-To

### 9.1 Requesting: ReplyToPublisher

Registered when `replyTo` is announced.

| Method | Result |
|---|---|
| `publishWithReply(Message[, MessageContext])` | `Promise<Message>` with the first response; fails with `TimeoutException` after the reply timeout, and with `MessagingException` at once on a response carrying `replyerror`. Every failure goes through the promise. |
| `publishWithReplyMany(Message[, MessageContext])` | `PushStream<Message>`; requires `replyToMany` (`FeatureNotSupportedException` thrown at once). The request is published when the stream is connected. The stream ends with the response carrying header `replycomplete=true` (not part of the stream) or when the consumer closes it; it fails with `MessagingException` if that response also carries `replyerror`, and with `TimeoutException` if no response arrives within the timeout since the request or the previous response. |

Reply headers (`MessagingConstants`, both valid CloudEvents extension names):

| Constant | Header | Value | Meaning |
|---|---|---|---|
| `HEADER_REPLY_COMPLETE` | `replycomplete` | `true` | Last, empty response of a response stream. |
| `HEADER_REPLY_ERROR` | `replyerror` | short reason | The exchange ended with a failure of the responder. |

The requester's `MessagingException` reads `The responder failed: <reason>`, the remote reason cut
to 64 characters without control characters.

Rules:

- The implementation MUST subscribe to the reply channel **before** it publishes the request,
  MUST accept only responses with the correlation id of the request, and MUST unsubscribe when the
  exchange is over.
- Without correlation id one is generated (UUID) if `generateCorrelationId` is announced,
  otherwise `FeatureNotSupportedException`. Without reply-to channel a generated one is used if
  `generateReplyChannel` is announced, otherwise `FeatureNotSupportedException`.
- A request needs a channel (`MessagingException` otherwise) and a connected instance
  (`MessagingException` otherwise). A second request with a correlation id that is still waiting
  fails with `MessagingException`.
- Timeout: `osgi.messaging.replyTo.timeout` if positive, else configuration `replyTimeout`.

### 9.2 Generated Reply Channels

Requests without reply-to share one generated channel per instance, subscribed once and dispatched
by correlation id. Its name is `<replyChannelPrefix>/<UUID>` with the default prefix
`DEFAULT_REPLY_CHANNEL_PREFIX + clientId`, i.e. `osgi/messaging/reply/<clientId>/<uuid>`. A
request naming its own reply channel gets a subscription of its own for the exchange.

### 9.3 Answering: Whiteboard Handlers

Requires `replyToSubscription`. A handler is a service of type `ReplyToSingleSubscriptionHandler`
(one response, `null` = none) or `ReplyToManySubscriptionHandler` (a `PushStream` of responses).

| Property | Constant | Required | Meaning |
|---|---|---|---|
| `osgi.messaging.target` | `MESSAGING_TARGET_PROPERTY` | yes | Filter over the runtime service properties of the instances to bind to. |
| `osgi.messaging.replyToSubscription.channel` | `REPLY_TO_SUBSCRIPTION_CHANNEL_PROPERTY` | yes | Request channel. |
| `osgi.messaging.replyToSubscription.channelExtension` | `REPLY_TO_SUBSCRIPTION_CHANNEL_EXTENSION_PROPERTY` | no | Extension of the request channel. |
| `osgi.messaging.replyToSubscription.replyChannel` | `REPLY_TO_SUBSCRIPTION_REPLY_CHANNEL_PROPERTY` | no | Channel for requests without reply-to; without it they get no response. |
| `osgi.messaging.replyToSubscription.channelType` | `REPLY_TO_SUBSCRIPTION_CHANNEL_TYPE_PROPERTY` | no | `queue` for competing handlers, or `topic`. |

Component property types: `@MessagingTarget("(osgi.messaging.name=x)")` and
`@ReplyToSubscription(channel = ..., channelExtension = "", replyChannel = "", channelType = "")`;
empty values mean "not set".

Rules:

- A handler without target or channel property MUST NOT be bound. A many-handler is not bound on
  an instance without `replyToMany` (logged). A handler whose subscription fails the feature check
  is not bound.
- Per request, the handler gets a builder already set to reply channel and the request's
  correlation id (a fresh one per response for many-handlers). A single response is published if
  non-null; a many-stream's messages are published, followed by an empty response with header
  `replycomplete=true` (`HEADER_REPLY_COMPLETE`) when the stream ends.
- A single handler that throws is answered with an empty response carrying
  `replyerror=The responder failed`; a response stream that fails ends with `replycomplete=true`
  and `replyerror=The responder failed`. The reason MUST NOT carry exception text or other
  internals of the responder; the failure is logged on the responder side.
- Modifying the service properties rebinds; unregistering unbinds and ends the subscription.
- Handlers run on the worker threads. When more than `bufferSize` requests of one handler are in
  flight, the receiving thread runs the handler itself (caller runs), so broker flow control holds
  back further requests.

### 9.4 Default Reply Channel and Correlation

The response goes to the reply-to channel of the request, or to the handler's `replyChannel` if the
request names none. The correlation id of the request is copied if present.

### 9.5 Protocol Mapping

| Binding | Correlation id | Reply-to |
|---|---|---|
| memory | context field | context field |
| mqtt | correlation data (UTF-8) | response topic |
| amqp | `correlation_id` | `reply_to`; responses are published as topic messages to that routing key |
| mqtt3, websocket | only with `cloudEvents=structured` (envelope `correlationid`, `replyto`) | same |

### 9.6 Allowed Reply Channels

A request names its reply channel itself. A received request MUST be answered only if its reply-to
channel starts with one of the `replyToAllowed` prefixes — by default `osgi/messaging/reply/`
(`DEFAULT_REPLY_CHANNEL_PREFIX`) and the subtree of the configured `replyChannelPrefix`
(prefix + `/`, so `app/replies` does not allow `app/replies-admin`); `*` allows any. Configured
prefixes match as given. A refused
request is not handled at all (no handler side effects) and counted in
`ReplyToSubscriptionDTO.refusedRequests`. The handler's own `replyChannel` (configured, not chosen
by the sender) is not subject to this check.

---

## 10 Connection and Reconnect

### 10.1 States

| `ConnectionState` | Meaning |
|---|---|
| `CONNECTING` | Initial state until the first connection is made. |
| `CONNECTED` | Connected; subscriptions in place. |
| `DISCONNECTED` | Connection lost; the instance reconnects. |
| `CLOSED` | Stopped, or gave up after a fatal failure; no more reconnects. |

### 10.2 Reconnect Cycle

The instance is the only reconnect owner; the automatic reconnect of the client library MUST be
off (Paho `automaticReconnect=false`, RabbitMQ `automaticRecovery`/`topologyRecovery` off).

- Each attempt first releases the previous connection completely, then connects with a deadline of
  `connectTimeout`.
- Backoff of attempt *n* (n from 0): `b = min(M, d · 2^min(n,20))` with
  `d = max(10, reconnectDelay)`, `M = max(10, reconnectMaxDelay)`; the delay is drawn uniformly
  from `[b/2, b]` (equal jitter), so a fleet does not reconnect in lockstep.
- The backoff starts over only after a connection that held at least `reconnectMaxDelay`; a
  connection that drops at once (client id taken over, broker refusing after the handshake) keeps
  backing off.
- Fatal failures end the cycle: MQTT 5 reason codes `0x86`, `0x87`, `0x8A`, `0x8C`; MQTT 3.1.1
  failed authentication / not authorised; AMQP `AuthenticationFailureException`. The state becomes
  `CLOSED` and all subscription streams fail with the cause.
- After a successful connect, all subscriptions are restored **before** `CONNECTED` is reported;
  a subscription that cannot be restored fails its stream. `MessagingRuntimeDTO.reconnects`
  counts reconnects.

### 10.3 Events and Listeners

`ConnectionEvent(instanceName, previousState, state, reconnect, sessionPresent, timestamp, cause)`:
`previousState` is the real previous state — `CONNECTING` before the first `CONNECTED`,
`DISCONNECTED` before a reconnect;
`reconnect` is `true` when connected again after an earlier connection — messages published in
between may be lost and consumers SHOULD re-synchronise unless `sessionPresent` says the broker
resumed the session; `cause` is set for `DISCONNECTED` and `CLOSED`.

`ConnectionListener` is a whiteboard service:

- `osgi.messaging.target` selects instances by their runtime service properties; **without target
  the listener hears all instances** (unlike reply-to handlers).
- A listener registered while an instance is in a state is told that state at once with
  `previousState == null`.
- Events of one instance are delivered in order, on a worker thread; a listener MUST NOT block.
  Exceptions are logged.

---

## 11 CloudEvents

Optional per instance, configuration `cloudEvents` = `none` | `binary` | `structured`
(`CloudEventConstants.MODE_BINARY`, `MODE_STRUCTURED`), CloudEvents spec version `1.0`.
With a mode other than `none` the instance announces `cloudEvents`.

**API.** `builder.adapt(CloudEventMessageContextBuilder.class)` sets `id`, `source`, `type`,
`subject`, `dataSchema`, `time` or copies a `CloudEvent` (`withCloudEvent`). `type` and `source`
are required (`MessagingException` at build); a missing `id` is generated (UUID), a missing
`time` is the build time. The content type is `datacontenttype`; headers are the extension
attributes. Received CloudEvents have a `CloudEventMessageContext` whose `getCloudEvent()` gives
the attributes; `CloudEvent.getExtensions()` equals the message headers.

**Plain messages** published on a CloudEvents instance are sent as CloudEvents with a generated
id, `source` = `cloudEvents.source`, `type` = `cloudEvents.type`, the current time and the headers
as extensions. Received messages that are no CloudEvent keep a plain context.

**Binary mode**: attributes travel as protocol headers, data is the payload. It needs a protocol
with headers; a protocol without them refuses `binary` at start.

| Binding | Header prefix |
|---|---|
| memory | `ce-` |
| mqtt | none — user properties (CloudEvents MQTT binding) |
| amqp | `cloudEvents_` — application properties (CloudEvents AMQP binding) |
| mqtt3, websocket | refused, use `structured` |

**Structured mode**: the payload is a JSON envelope, content type `application/cloudevents+json`
(`STRUCTURED_JSON_CONTENT_TYPE`). The envelope carries content type (`datacontenttype`),
headers (as extensions), correlation (`correlationid`), reply-to (`replyto`) and content encoding
(`contentencoding`) for any protocol; such instances therefore additionally announce
`contentType`, `headers`, `replyTo`, `replyToMany`, `replyToSubscription`,
`generateCorrelationId`, `generateReplyChannel`. Data is embedded as JSON for `application/json`
or `*+json` content types if valid JSON, as string for `text/*`, otherwise as `data_base64`.
Header names become attribute names: lower-case letters and digits only. Native protocol
correlation id and reply-to take precedence over envelope values.

### 11.1 Received Messages That Are No CloudEvent

An instance with CloudEvents MUST by default take CloudEvents only: a received message that is no
CloudEvent — in the binary mode one without the CloudEvents headers, in the structured mode one
whose content type is not `application/cloudevents+json` or, on protocols without content type
(mqtt3, websocket), one that does not parse as an envelope with `specversion` 1.0, `id`, `source`
and `type` — is dropped, rejected without re-queue and counted in
`SubscriptionDTO.droppedMessages`. With `cloudEvents.acceptPlain=true` such a message is delivered
unchanged as a plain message: its context is no `CloudEventMessageContext`, and nothing is made up
for it. A message announced as envelope by its content type that is not a valid one is dropped in
both cases. Rationale: an instance configured for CloudEvents relies on their attributes; a
message without them is not silently treated as one (§14).

Envelope parsing limits are in §14.3.

---

## 12 Runtime Introspection and DTOs

`MessageServiceRuntime.getRuntimeDTO()` returns a snapshot; `getConnectionState()` the current
state.

`MessagingRuntimeDTO`:

| Field | Content |
|---|---|
| `serviceDTO` | `ServiceReferenceDTO` of the runtime service |
| `name`, `providerName` | instance name, `gecko` |
| `protocols`, `features` | as the service properties |
| `connectionURI` | URI without user info |
| `connectionState` | name of `ConnectionState` |
| `reconnects` | reconnects since start |
| `subscriptions` | `SubscriptionDTO[]` — application subscriptions only (reply-to internals excluded) |
| `replyToSubscriptions` | `ReplyToSubscriptionDTO[]` of bound handlers |

`SubscriptionDTO`: `channel` (`ChannelDTO`), `receivedMessages`, `droppedMessages` (undecodable,
or no CloudEvent on an instance that takes CloudEvents only).
`ChannelDTO`: `name`, `extension`, `connected` (subscribed on the broker right now).
`ReplyToSubscriptionDTO`: `handlerService`, `requestChannel`, `replyChannel` (or `null`), `many`,
`handledRequests`, `refusedRequests`.

---

## 13 Protocol Bindings

All bindings are DS components with `configurationPolicy = REQUIRE`, one instance per factory
configuration, building on `org.gecko.messaging.core`.

### 13.1 Feature Matrix

`●` announced, `○` announced only with `cloudEvents=structured`, `—` not announced. With
`cloudEvents=binary` (memory, mqtt, amqp only) the column changes only in `cloudEvents`.

| Feature | memory | mqtt | mqtt3 | amqp | websocket |
|---|---|---|---|---|---|
| `publishSubscribe` | ● | ● | ● | ● | ● |
| `pointToPoint` | ● | ● | ● | ● | — |
| `wildcard` | ● | ● | ● | ● | — |
| `channelExtension` | ● | — | — | ● | — |
| `contentType` | ● | ● | ○ | ● | ○ |
| `headers` | ● | ● | ○ | ● | ○ |
| `qos` | ● | ● | ● | ● (0, 1) | — |
| `timeToLive` | ● | ● | — | ● | — |
| `retain` | ● | ● | ● | — | — |
| `autoCreate` | ● | ● | ● | ● | ● |
| `durable` | ● | — | — | ● | — |
| `acknowledge` | ● | ● | ● | ● | — |
| `replyTo` | ● | ● | ○ | ● | ○ |
| `replyToMany` | ● | ● | ○ | ● | ○ |
| `replyToSubscription` | ● | ● | ○ | ● | ○ |
| `generateCorrelationId` | ● | ● | ○ | ● | ○ |
| `generateReplyChannel` | ● | ● | ○ | ● | ○ |
| `cloudEvents` | binary/structured | binary/structured | structured | binary/structured | structured |

### 13.2 MQTT 5 (`mqtt`)

| Item | Value |
|---|---|
| Protocol / factory PID | `mqtt` / `org.gecko.messaging.mqtt` |
| Client | Eclipse Paho MQTT v5, one `MqttAsyncClient` per instance, `MemoryPersistence`, manual acks |
| URI schemes | `tcp://`, `ssl://`, `ws://`, `wss://` |
| Channels | topic = channel; queue = `$share/<sharedGroup>/<channel>`; wildcards `+`, `#` |
| QoS | 0, 1, 2 on publish and subscribe; received messages carry their QoS in `osgi.messaging.qos` and `osgi.messaging.retain=true` if retained |
| Context mapping | content type → content type; content encoding → user property `content-encoding`; headers → user properties (values as String); correlation id → correlation data; reply-to → response topic; TTL → message expiry interval (s) |
| Acknowledgement | QoS 1/2 acknowledged after the decision; reject = acknowledge and drop; re-queue (queues only) = republish with user property `requeued`, at most once |
| Size limit | `maxMessageSize` on publish and as Paho maximum packet size; announced in `osgi.messaging.maxMessageSize` |
| Fatal | reason codes `0x86`, `0x87`, `0x8A`, `0x8C` |

| Key | Default | Meaning |
|---|---|---|
| `cleanStart` | `true` | MQTT clean start |
| `sessionExpiryInterval` | `0` | s |
| `keepAliveInterval` | `30` | s |
| `receiveMaximum` | `100` | unacknowledged QoS 1/2 messages from the broker |
| `qos` | `1` | default QoS |
| `maxMessageSize` | `1048576` | bytes, sent and received |
| `sharedGroup` | `messaging` (`DEFAULT_SHARED_GROUP`) | group of shared subscriptions; one group for all instances, so every instance on a queue competes |

Several local subscriptions of the same topic filter share one broker subscription
(`SubscriptionGroup`, also used by mqtt3 and websocket): every one receives a message on a topic,
one in turn on a queue, and the message is completed on the broker once all have decided. A later
subscription needing a higher QoS subscribes the filter again with it; the broker subscription
keeps the QoS the broker granted and is not lowered when that subscription ends. The repeated
subscribe sets retain handling 1, so existing subscriptions do not receive a retained message
again — and a later local subscription of an already subscribed filter does not receive it at all.

### 13.3 MQTT 3.1.1 (`mqtt3`)

A binding of its own for brokers and devices without MQTT 5.

| Item | Value |
|---|---|
| Protocol / factory PID | `mqtt3` / `org.gecko.messaging.mqtt3` |
| Client | Eclipse Paho MQTT v3, protocol version 3.1.1, manual acks |
| Channels | as mqtt; queues via `$share/<sharedGroup>/<channel>` — not defined by MQTT 3.1.1, only where the broker offers it (Mosquitto, HiveMQ, EMQX) |
| Wire | topic, payload, QoS, retain flag only; no content type, headers, TTL or reply-to without structured CloudEvents |
| Acknowledgement | as mqtt; re-queue detected by fingerprint (at most 1024 kept) |
| Fatal | failed authentication, not authorised |

| Key | Default | Meaning |
|---|---|---|
| `cleanSession` | `true` | MQTT clean session |
| `keepAliveInterval` | `30` | s |
| `maxInflight` | `100` | in-flight messages |
| `qos` | `1` | default QoS |
| `maxMessageSize` | `1048576` | bytes, sent and largest inbound packet |
| `sharedGroup` | `messaging` (`DEFAULT_SHARED_GROUP`) | group of shared subscriptions; one group for all instances, so every instance on a queue competes |

Shared broker subscriptions as mqtt. MQTT 3.1.1 has no retain handling: when a filter is
subscribed again for a higher QoS, the broker sends its retained message again, and existing
subscriptions may receive it twice.

### 13.4 AMQP 0-9-1 (`amqp`)

| Item | Value |
|---|---|
| Protocol / factory PID | `amqp` / `org.gecko.messaging.amqp` |
| Client | RabbitMQ Java client, one connection per instance, automatic recovery off |
| URI | `amqp://`, `amqps://` with user, password, virtual host |
| Topics | routing key on `exchange` (default `amq.topic`); each subscription an exclusive, auto-delete, server-named queue bound with the channel; wildcards `*`, `#`, separator `.` |
| Channel extension | channel = exchange (type `topic`), extension = routing key |
| Queues | named queue on the default exchange; competing consumers; no extension |
| QoS | 0 (no confirm, auto-ack on receive) and 1 (publisher confirm, manual ack); 2 refused with `FeatureNotSupportedException`; configuring `qos` other than 0/1 fails activation |
| Context mapping | content type, content encoding, correlation id, reply-to → basic properties; headers → `headers` (received values of other field types — timestamp, table, array, byte[] — become Strings); TTL → `expiration`; `durable` → delivery mode 2 |
| Acknowledgement | `basicAck`/`basicNack`; re-queue unless `redelivered` |
| Retain | not supported |
| Size limit | `maxMessageSize` on publish and for received bodies (`setMaxInboundMessageBodySize`, instead of the client's 64 MiB); announced in `osgi.messaging.maxMessageSize` |
| Fatal | `AuthenticationFailureException` |

| Key | Default | Meaning |
|---|---|---|
| `exchange` | `amq.topic` | exchange of topics |
| `heartbeat` | `30` | s |
| `prefetch` | `100` | unacknowledged messages per subscription |
| `maxMessageSize` | `1048576` | bytes, bodies sent and received |
| `qos` | `1` | default QoS, 0 or 1 |

### 13.5 WebSocket (`websocket`)

| Item | Value |
|---|---|
| Protocol / factory PID | `websocket` / `org.gecko.messaging.websocket` |
| Client | Jetty 12 WebSocket client |
| Model | raw WebSocket; channel `a/b` of base `ws://host/m` is the WebSocket `ws://host/m/a/b`; a hub relays every message of a path to all sessions of the path, **including the sender** |
| Sessions | one per channel in use (publish and receive) plus one control session on the base URI; the loss of any session triggers reconnect and restore; every session pinged each `keepAliveInterval` |
| Channels | relative path; empty, `.` and `..` segments refused with `MessagingException`; no wildcards, no queues |
| Frames | sent as binary; received text frames taken as UTF-8 bytes |
| Features | `publishSubscribe`, `autoCreate`; content type, headers and reply-to only with structured CloudEvents; binary CloudEvents refused |

| Key | Default | Meaning |
|---|---|---|
| `uri` | required | `ws://` or `wss://` base of the channel paths |
| `keepAliveInterval` | `30` | s; idle timeout is three times that |
| `maxThreads` | `8` | client threads |
| `maxMessageSize` | `1048576` | bytes, checked before sending, largest message received; announced in `osgi.messaging.maxMessageSize` |
| `maxPublishSessions` | `16` (min 1) | sessions kept for channels only published to; least recently used closed beyond |

### 13.6 WebSocket Hub (`org.gecko.messaging.websocket.hub`)

Not a messaging instance: an embedded Jetty 12 server relaying messages per URL path, the
counterpart of §13.5. It keeps nothing — a message reaches the sessions open at that moment.
**It has no authentication and is meant for trusted networks only.**

| Key | Default | Meaning |
|---|---|---|
| `host` | `localhost` | interface; `0.0.0.0` for all |
| `port` | `8090` | `0` for any free port |
| `idleTimeout` | `120` | s |
| `maxMessageSize` | `1048576` | bytes |
| `maxThreads` | `16` | server threads |
| `maxConnections` | `256` | open connections |
| `maxOutgoingFrames` | `64` | frames queued per session; a slower session is closed (policy violation) |
| `allowedOrigins` | none | origins a browser page may connect from; an upgrade with another `Origin` gets 403 |

### 13.7 In-Memory (`memory`)

| Item | Value |
|---|---|
| Protocol / factory PID | `memory` / `org.gecko.messaging.memory` |
| Broker | in the JVM, named by `uri` `memory://<broker>`, default `default`; instances on the same broker talk to each other; one delivery thread per broker |
| Channels | MQTT topic matching (`+`, `#`, `/`); queues round robin; extension must match if the subscription has one |
| Features | all except `cloudEvents` (which follows the configuration) |
| Purpose | tests of messaging applications and the reference TCK run without external broker |

QoS 1 and 2 are met by lossless in-process delivery; `timeToLive` applies to the kept (retained)
message — an expired one reaches no later subscriber, others are delivered at once; `durable`
channels last as long as the broker. No size limit. `InMemoryBroker`
offers `dropConnections()` and `setAvailable(boolean)` for tests.

---

## 14 Security

Security is a design principle of this specification: implementations MUST be safe by default and
MUST bound every resource a remote party can make them use.

### 14.1 Re-queue Bound

A message MUST be re-queued at most once (`Acknowledger.MAX_REQUEUES = 1`, fixed), and only on a
queue. A reject with re-queue of a re-queued message drops it, so a poison message cannot cycle
and occupy receivers; a reject on a topic never re-queues, so one receiver cannot multiply a
message to every subscriber. A failing acknowledgement decision (exception in function or filter)
rejects without re-queue. Undecodable messages are rejected without re-queue.

### 14.2 Reply Channels

A request chooses its reply channel; without a check, a sender could direct responses (and
handler side effects) to any channel the answering instance may write to. The whiteboard MUST
answer only reply channels starting with `osgi/messaging/reply/`, the subtree of the configured
`replyChannelPrefix` (prefix + `/`), or a configured `replyToAllowed` prefix (`*` for any);
refused requests are not handled and counted (`refusedRequests`).

A failing responder answers with `replyerror=The responder failed` (§9.3): the error response
MUST NOT carry exception text or other internals of the responder. The requester quotes the
received reason cut to 64 characters without control characters.

### 14.3 CloudEvents Envelope

The structured envelope parser (no dependency, no reflection) MUST refuse: nesting deeper than 64
levels (`Json.MAX_DEPTH`), number tokens longer than 64 characters (`Json.MAX_NUMBER_LENGTH`),
duplicate members, malformed escapes, control characters in strings, trailing content, a
`specversion` other than `1.0`, and events without `id`, `source` or `type`. Received values
quoted in exception messages are cut to 64 characters with control characters replaced by `?`, so
a sender can neither flood nor forge log lines.

An instance with CloudEvents takes CloudEvents only unless `cloudEvents.acceptPlain` is set
(§11.1): a message without the attributes is not treated as an event, and none are made up for it.

### 14.4 Credentials

- Service properties and `MessagingRuntimeDTO.connectionURI` show `uri` without user info.
- Configuration keys whose normalised name (lower case, without `-`, `_`, `.`) contains
  `password`, `secret`, `token`, `credential`, `apikey` or `privatekey`, and keys starting with
  `.`, are not service properties.
- When credentials (user name, password or user info) go over a plaintext scheme (`tcp`, `mqtt`,
  `ws`, `amqp`), the instance logs a warning recommending a TLS scheme.

### 14.5 Bounded Resources

| Resource | Bound |
|---|---|
| Threads | 1 scheduler + `workerThreads` per instance; WebSocket client `maxThreads` |
| Subscription buffers | `bufferSize` with `queuePolicy` (§7) |
| Unacknowledged messages | MQTT `receiveMaximum`/`maxInflight`, AMQP `prefetch` |
| Reply-to handlers | at most `bufferSize` requests in flight per handler, then caller runs |
| Payload size | `maxMessageSize` (MQTT, MQTT 3.1.1, AMQP, WebSocket; default 1 MiB), checked before sending and on receipt |
| WebSocket sessions | at most `maxPublishSessions` publish-only sessions (a request names its reply channel; unbounded, a sender could open a session per request) |
| WebSocket paths | no empty, `.` or `..` segments: no channel leaves the base path |
| Hub | `maxMessageSize`, `maxConnections` (256), `maxOutgoingFrames` (64, slow sessions closed), `Origin` check against `allowedOrigins`, binds `localhost` by default, **no authentication — trusted networks only** |
| Re-queue memory (mqtt3) | 1024 fingerprints |
| Reconnect | minimum delay 10 ms, jittered exponential backoff |

---

## 15 Technology Compatibility Kit

Bundle `org.gecko.messaging.tck` (package `org.gecko.messaging.tck`) holds protocol-neutral JUnit 5
tests on OSGi-Test. They test the instance selected by a filter, check every announced feature
and that every feature not announced fails.

### 15.1 Framework Properties (`Tck`)

| Property | Default | Meaning |
|---|---|---|
| `org.gecko.messaging.tck.filter` | `(osgi.messaging.name=tck)` | selects the instance under test |
| `org.gecko.messaging.tck.timeout` | `10000` | ms to wait for anything |
| `org.gecko.messaging.tck.separator` | `/` | separator of channel segments |
| `org.gecko.messaging.tck.wildcard.single` | `+` | single-level wildcard |
| `org.gecko.messaging.tck.wildcard.multi` | `#` | multi-level wildcard |
| `org.gecko.messaging.tck.proxy.listen` | run project default | port of the `TcpProxy` |
| `org.gecko.messaging.tck.proxy.broker` | run project default | `host:port` of the broker |

Channels are unique per run (`tck/<random>/...`). Every test has a 60 s timeout; a service or
connection that never appeared fails later tests at once.

### 15.2 Feature-Driven Skipping

Positive tests are skipped (`assumeTrue`) when the feature is not announced; the negative tests in
`FeatureEnforcementTest` run exactly when it is not announced (`assumeFalse`) and expect
`FeatureNotSupportedException` with the feature name. Reconnect tests are skipped without a
`BrokerControl` service.

### 15.3 BrokerControl SPI and TcpProxy

`org.gecko.messaging.tck.spi.BrokerControl` (consumer type): `dropConnections()` drops every
connection of the instance under test (new ones accepted); `setAvailable(boolean)` makes the broker
unreachable or reachable. `TcpProxy` is an abstract `BrokerControl`: a TCP proxy between the
instances and the broker. A run project registers a subclass as immediate component, calls
`start(context, listenPort, "host:port")` on activation and `stop()` on deactivation; instances
connect to the proxy port.

### 15.4 Run Projects

A run project (`org.gecko.messaging.<binding>.tck`) contains:

- `bnd.bnd` — buildpath on the TCK; `-includeresource: OSGI-INF/configurator/=configs/` and a
  requirement on `osgi.configurator`; `-dependson` on API, core, binding and TCK.
- `configs/tck.json` — Configurator resource with two factory configurations: `<pid>~tck`
  (`osgi.messaging.name=tck`) and `<pid>~tckce` (`osgi.messaging.name=tck-cloudevents`,
  `cloudEvents=structured`); short `reconnectDelay`/`reconnectMaxDelay` (50/200 ms) and
  `connectTimeout` 3000 ms, URI pointing at the proxy.
- A `BrokerControl` component (`TcpProxy` subclass; in-memory: `InMemoryBrokerControl`).
- `tck.bndrun` and `tck-cloudevents.bndrun` — include `cnf/ext/tck-run.bnd` (Felix 7, JavaSE-21,
  `biz.aQute.tester.junit-platform`, SCR, Config Admin, Configurator, timeout 10000) and require
  TCK, binding and run project; the CloudEvents run sets the filter to
  `(osgi.messaging.name=tck-cloudevents)`.

Example (`org.gecko.messaging.mqtt.tck/configs/tck.json`):

```json
{
  ":configurator:resource-version": 1,
  "org.gecko.messaging.mqtt~tck": {
    "osgi.messaging.name": "tck", "uri": "tcp://localhost:11884",
    "reconnectDelay:Long": 50, "reconnectMaxDelay:Long": 200, "connectTimeout:Long": 3000
  },
  "org.gecko.messaging.mqtt~tckce": {
    "osgi.messaging.name": "tck-cloudevents", "uri": "tcp://localhost:11884",
    "reconnectDelay:Long": 50, "reconnectMaxDelay:Long": 200, "connectTimeout:Long": 3000,
    "cloudEvents": "structured"
  }
}
```

### 15.5 The Thirteen Runs

| # | Project | bndrun | Broker | BrokerControl | Extra properties |
|---|---|---|---|---|---|
| 1 | memory.tck | tck | in-memory `memory://tck` | `InMemoryBrokerControl` | — |
| 2 | memory.tck | tck-cloudevents | in-memory `memory://tck-cloudevents` | same | `org.gecko.messaging.memory.tck.broker=tck-cloudevents` |
| 3 | mqtt.tck | tck | Mosquitto 2 `:11883` (`docker/brokers.sh`) | `TcpProxy` `:11884` | — |
| 4 | mqtt.tck | tck-cloudevents | same | same | filter |
| 5 | mqtt3.tck | tck | Mosquitto 2 `:11883` | `TcpProxy` `:11886` | — |
| 6 | mqtt3.tck | tck-cloudevents | same | same | filter |
| 7 | amqp.tck | tck | RabbitMQ 4.1 `:15675` | `TcpProxy` `:15676` | separator `.`, wildcard.single `*` |
| 8 | amqp.tck | tck-cloudevents | same | same | filter, separator, wildcard |
| 9 | websocket.tck | tck | `WebSocketHub` `:18090` (in the run) | `TcpProxy` `:18091` | — |
| 10 | websocket.tck | tck-cloudevents | same | same | filter |
| 11 | memory.tck | tck-cloudevents-binary | in-memory `memory://tck-ce-binary` | `InMemoryBrokerControl` | filter, `org.gecko.messaging.memory.tck.broker=tck-ce-binary` |
| 12 | mqtt.tck | tck-cloudevents-binary | Mosquitto 2 `:11883` | `TcpProxy` `:11884` | filter |
| 13 | amqp.tck | tck-cloudevents-binary | RabbitMQ 4.1 `:15675` | `TcpProxy` `:15676` | filter, separator, wildcard |

Runs 11–13 use the CloudEvents binary mode, where the protocol has headers; the other
`tck-cloudevents` runs the structured mode.

### 15.6 Load Profiles

Every run project also has a `load.bndrun` (`LoadTest`, only with the framework property
`org.gecko.messaging.tck.load`) against an instance `tck-load` with a keep-alive of 2 s and a
buffer of 256: a protocol thread held up by a full buffer then shows as a reconnect.

| Profile | Load | Asserted |
|---|---|---|
| `burstIsAbsorbed` | 500 messages at full speed, subscriber 2 ms per message | all, in order; no reconnect; the publisher is done before the subscriber |
| `sustainedLoadTheSubscriberKeepsUpWith` | 500/s for 5 s | all, in order; no reconnect |
| `sustainedLoadAboveTheSubscriberIsThrottled` (QoS) | 200/s for 6 s, subscriber 125/s | all, in order; no reconnect; the publisher keeps its rate |
| `aStalledSubscriberDoesNotBlockTheConnection` (QoS) | 100/s for 8 s, the subscriber stalls 10 s once | all, in order; no reconnect; the publisher keeps its rate |

Throughput and latency (p50, p99, max) are reported as JUnit report entries, not asserted.
`LOAD_SCALE` in the environment scales counts and durations (CI: 1). Without broker flow
control (no `qos` feature, WebSocket) the throttling profiles are skipped: a subscriber that stays
slower than the sender loses messages there.

### 15.7 Test Classes

| Class | Tests |
|---|---|
| `InstanceServicesTest` | `everyServiceCarriesTheInstanceProperties`, `builderIsPrototypeScoped`, `replyToPublisherExactlyWhenAnnounced`, `runtimeDescribesTheInstance`, `buildersAdaptOnlyToAnnouncedFeatures` |
| `PublishSubscribeTest` | `roundTrip`, `allByteValuesSurvive`, `emptyPayload`, `publishToGivenChannel`, `publishWithBuilderFunction`, `contextParameterReplacesTheMessageContext`, `subscribeWithContext`, `manyMessagesArriveInOrder`, `everySubscriberOfATopicReceives`, `closingTheStreamEndsTheSubscription`, `otherChannelsAreNotReceived` |
| `MessageContextTest` | `contentTypeAndEncodingTravel`, `headersTravel`, `correlationIdAndReplyToTravel`, `contextsAreImmutableAndBuildersReset`, `withContextStartsFromAnotherContext`, `payloadBuffersAreIndependent` |
| `ChannelTypesTest` | `queueDeliversEachMessageToOneReceiver`, `singleLevelWildcard`, `multiLevelWildcard`, `channelExtension` |
| `DeliveryOptionsTest` | `atLeastOnceIsConfirmed`, `atMostOnceIsAlwaysAccepted`, `invalidQosFails`, `timeToLiveIsAccepted`, `retainedMessageReachesLateSubscribers`, `aMessageAboveTheAnnouncedLimitFails` (runs announcing a limit) |
| `FeatureEnforcementTest` | `qos`, `headers`, `contentType`, `timeToLive`, `retain`, `channelExtension`, `queue`, `replyTo`, `acknowledge`, `cloudEvents`, `wildcard`, `unknownChannelTypeFails` |
| `AcknowledgeTest` | `filterDecidesBeforeTheStream`, `functionDecidesBeforeTheStream`, `manualAcknowledgeInTheStream`, `rejectedMessageIsRequeued`, `aMessageIsRequeuedAtMostOnce`, `aRejectOnATopicIsNotRequeued` |
| `ReplyToTest` | `singleResponseWithOwnCorrelationAndChannel`, `generatedCorrelationAndReplyChannel`, `concurrentRequestsGetTheirOwnResponses`, `noResponseTimesOut`, `manyResponsesEndWithTheCompleteHeader`, `manyResponsesTimeOutWithoutEnd` |
| `ReplyToWhiteboardTest` | `requestNamingAForeignReplyChannelIsNotAnswered`, `singleHandlerAnswers`, `manyHandlerStreamsAndCompletes`, `defaultReplyChannelForRequestsWithout`, `handlerWithoutTargetIsNotBound`, `unregisteringUnbinds`, `aFailingHandlerFailsTheRequestAtOnce`, `aFailingResponseStreamEndsWithAnError` |
| `RuntimeTest` | `subscriptionsAreListed`, `listenerIsToldTheCurrentState`, `listenerWithForeignTargetHearsNothing` |
| `ReconnectTest` | `subscriptionsSurviveAConnectionLoss` (also asserts `DISCONNECTED` as previous state of the reconnect event), `publishFailsWhileDisconnectedAndWorksAfterwards` |
| `CloudEventsTest` | `attributesAndDataTravel`, `extensionsAreHeaders`, `generatedIdAndTime`, `plainMessagesTravelAsCloudEventsToo`, `aMessageThatIsNoCloudEventIsDroppedByDefault` |

---

## 16 Requirements Traceability

RFP 0192 lists the id MSG100 twice, for two different requirements. Both are kept here and labelled
**MSG100a** (fail on unknown or unsupported intents) and **MSG100b** (asynchronously send messages
to a channel).

| Id | Level | Requirement | Status | Section | TCK |
|---|---|---|---|---|---|
| MSG010 | MUST | Technology, vendor and protocol independent | Met | 1, 13 | all classes, run against five bindings (§15.5) |
| MSG030 | MUST | Configurable: address space, timeouts, QoS | Met | 2.2, 13 | `ReconnectTest` (reconnect settings), `DeliveryOptionsTest` |
| MSG050 | MUST | Announce capabilities/intents to consumers | Met | 2.3, 3 | `InstanceServicesTest.everyServiceCarriesTheInstanceProperties`, `runtimeDescribesTheInstance` |
| MSG060 | MUST | Information about registered channels, connection states | Met | 10, 12 | `RuntimeTest.subscriptionsAreListed`, `listenerIsToldTheCurrentState`, `listenerWithForeignTargetHearsNothing`; `ReconnectTest.subscriptionsSurviveAConnectionLoss` (previous state `DISCONNECTED`) |
| MSG070 | MUST | Asynchronous programming model | Met | 6, 7 | `PublishSubscribeTest.roundTrip`, `publishWithBuilderFunction` |
| MSG080 | MUST | Client API | Met | 5–9 | `PublishSubscribeTest`, `MessageContextTest` |
| MSG090 | MUST | Respect requested intents | Met | 3, 6, 8 | `DeliveryOptionsTest.*`, `ChannelTypesTest.*`, `AcknowledgeTest.*` (incl. `aRejectOnATopicIsNotRequeued`) |
| MSG095 | MUST | Announce supported intents | Met | 3, 13.1 | `InstanceServicesTest.buildersAdaptOnlyToAnnouncedFeatures`, `replyToPublisherExactlyWhenAnnounced` |
| MSG100a | MUST | Fail on unknown or unsupported intents | Met — unknown, implementation-specific extension keys are ignored (§3) | 3, 4.2 | `FeatureEnforcementTest.*` (incl. `wildcard`, `unknownChannelTypeFails`), `DeliveryOptionsTest.invalidQosFails`, `InstanceServicesTest.buildersAdaptOnlyToAnnouncedFeatures` |
| MSG100b | MUST | Asynchronously send messages to a channel | Met | 6 | `PublishSubscribeTest.roundTrip`, `publishToGivenChannel`, `contextParameterReplacesTheMessageContext` |
| MSG120 | MUST | Point-to-point channels | Met (not WebSocket) | 4.1 | `ChannelTypesTest.queueDeliversEachMessageToOneReceiver` |
| MSG130 | MUST | Publish-subscribe channels | Met | 4.1 | `PublishSubscribeTest.everySubscriberOfATopicReceives` |
| MSG140 | MUST | Quality of service | Met (AMQP 0–1, WebSocket 0) | 6 | `DeliveryOptionsTest.atLeastOnceIsConfirmed`, `atMostOnceIsAlwaysAccepted`, `invalidQosFails`, `subscriptionsOfOneChannelWithDifferentQosAllReceive` |
| MSG150 | MUST | Send-and-forget and send-and-forward | Met | 6 | `DeliveryOptionsTest.atMostOnceIsAlwaysAccepted`, `atLeastOnceIsConfirmed` |
| MSG160 | SHOULD | Reply-to, caller and receiver side | Met | 9 | `ReplyToTest.*`, `ReplyToWhiteboardTest.*` (incl. `requestNamingAForeignReplyChannelIsNotAnswered`, `aFailingHandlerFailsTheRequestAtOnce`, `aFailingResponseStreamEndsWithAnError`) |
| MSG170 | SHOULD | Filter semantics: exchange/routing key, wildcards | Met (memory, MQTT, AMQP) | 4.2, 4.3 | `ChannelTypesTest.singleLevelWildcard`, `multiLevelWildcard`, `channelExtension` |
| MSG180 | MAY | Auto-create / do-not-autocreate | Met — do-not-autocreate kept on AMQP, refused with `FeatureNotSupportedException` where channels are created on first use (MQTT) | 4.4 | `ChannelTypesTest.noAutoCreateIsKeptOrRefused`, `DeliveryOptionsTest.durableQueueDelivers` |
| MSG200 | MUST | Byte bodies | Met | 5.1 | `PublishSubscribeTest.allByteValuesSurvive`, `emptyPayload`; `MessageContextTest.payloadBuffersAreIndependent` |
| MSG205 | MAY / SHOULD signal | Size limits, signalled | Met — `osgi.messaging.maxMessageSize` on mqtt, mqtt3, amqp, websocket; memory unlimited | 2.3, 6 | `DeliveryOptionsTest.aMessageAboveTheAnnouncedLimitFails` |
| MSG210 | SHOULD | Further properties: sequencing, correlation | Partly — correlation and per-subscription order, no sequence numbers | 5.2, 7, 9 | `MessageContextTest.correlationIdAndReplyToTravel`, `headersTravel`; `PublishSubscribeTest.manyMessagesArriveInOrder` |
| MSG220 | MAY | Content encoding | Met | 5.2 | `MessageContextTest.contentTypeAndEncodingTravel` |
| MSG230 | MAY | Time-to-live | Met (MQTT 5, AMQP; memory for retained messages) | 6, 13.7 | `DeliveryOptionsTest.timeToLiveIsAccepted` |
| MSG240 | MAY | Manual acknowledge/reject | Met | 8 | `AcknowledgeTest.*` (re-queue at most once, only on queues) |
| MSG250 | MAY | Journalling | Not supported | — | — |
| MSG260 | MUST | Identify the channel a message was received on | Met | 4.2, 5.2 | `PublishSubscribeTest.roundTrip`, `ChannelTypesTest.singleLevelWildcard`, `channelExtension` |

Out of scope: journalling (MSG250), exactly-once over AMQP, retained messages over AMQP and
WebSocket, buffering of messages published while disconnected.

---

## 17 Open Issues

### 17.1 Behaviour to Decide

1. **Unknown extension keys** (including unknown `osgi.messaging.*` keys) are ignored rather than
   refused; only known keys with unsupported values fail (MSG100a).

### 17.2 Gaps

2. **No sequence numbers** (MSG210 Partly: correlation and per-subscription order only) and no
   journalling (MSG250).
3. **The envelope limits of §14.3** (depth, number length) are unit-tested in core only, not in
   the TCK.
4. **MQTT does not announce `durable`**; sessions are configured with
   `cleanStart`/`sessionExpiryInterval` (mqtt) and `cleanSession` (mqtt3).

### 17.3 Resolved since the first draft

- Received messages that are no CloudEvent: dropped and counted by default, delivered unchanged
  with `cloudEvents.acceptPlain` (§11.1).
- GitHub #12: `AbstractMessagingInstance` split — `CloudEventBinding` (chosen once at start),
  the acknowledgement decision in `AcknowledgeSupport`, the services in `OsgiBinding`,
  `Extensions` for extension values, `SimpleMessageContextBuilder.buildSimple()`, a `doClose()`
  hook with `stop()` final.
- GitHub #13: shared MQTT broker subscriptions take the highest QoS (`SubscriptionGroup`, §13.2).
- Reserved MQTT user properties `requeued` and `content-encoding` are refused as application
  headers; `autoCreate=false` is refused where channels are created on first use (MSG180).
- TCK runs for `durable`, `autoCreate` and the CloudEvents binary mode (§15.5).

Wildcard enforcement, size limits on AMQP and WebSocket, the previous state of reconnect events,
feature names from `adapt()`, the `MessagePublisher`/`MessageSubscription` failure Javadoc,
`queuePolicy` and `configureResponse` Javadoc, error responses (`replyerror`), the MQTT shared
group `messaging`, re-queue only on queues, AMQP header types, comma-separated `replyToAllowed`
with the own prefix as subtree, and time-to-live of retained in-memory messages.
