# Gecko Messaging 2.0 — User Guide

This guide shows how to set up and use Gecko Messaging, case by case. It names every
configuration key and explains when to change it. The normative rules are in the
[specification](../spec/messaging.md) (cited as §n). Moving from 1.x is covered in
[migration.md](migration.md).

Contents

1. [Concepts](#1-concepts)
2. [Setting up](#2-setting-up)
3. [Configuration reference](#3-configuration-reference)
4. [Publishing](#4-publishing)
5. [Subscribing](#5-subscribing)
6. [Acknowledgement](#6-acknowledgement)
7. [Buffers and flow control](#7-buffers-and-flow-control)
8. [Request and response](#8-request-and-response)
9. [Duplex sessions](#9-duplex-sessions)
10. [Flow.Publisher](#10-flowpublisher)
11. [CloudEvents](#11-cloudevents)
12. [Connection state and monitoring](#12-connection-state-and-monitoring)
13. [Security defaults and how to open them](#13-security-defaults-and-how-to-open-them)
14. [Troubleshooting](#14-troubleshooting)

---

## 1 Concepts

- **Instance.** One factory configuration of a binding (`mqtt`, `mqtt3`, `amqp`, `websocket`,
  `memory`) is one *messaging instance*: one connection to one broker. It registers the
  services `MessagePublisher`, `MessageSubscription`, `MessageContextBuilder` (prototype scope),
  `MessageServiceRuntime` and, where supported, `ReplyToPublisher`. All of them carry the same
  service properties, among them `osgi.messaging.name`. You select an instance by name, for
  example `@Reference(target = "(osgi.messaging.name=sensors)")`. Several instances can run
  side by side.
- **Channel.** The address of a message: an MQTT topic, an AMQP exchange/routing key or queue, a
  WebSocket path. A channel is a *topic* (every subscriber receives each message) or a *queue*
  (one of the subscribers receives it), chosen per message and subscription (§4.1).
- **Message and context.** A `Message` is an immutable payload plus a `MessageContext` (channel,
  content type, correlation id, reply-to, headers, extensions). *Headers* travel to the
  receiver. *Extensions* are options for the instance (QoS, time-to-live, buffer settings,
  timeouts) and are not sent as headers.
- **Features.** An instance announces what it supports in the service property
  `osgi.messaging.feature`. Anything you use that the instance does not announce fails at once
  with `FeatureNotSupportedException`. Nothing silently does less. Check the features in the
  reference filter if your code depends on one:
  `(&(osgi.messaging.name=sensors)(osgi.messaging.feature=acknowledge))`. The feature matrix
  per binding is in §13.1.
- **Asynchronous.** Publishing returns an OSGi `Promise`, subscribing an OSGi `PushStream`.

## 2 Setting up

### 2.1 Build

In `cnf/build.bnd` of the workspace:

```
-library: geckoMessaging
```

In the `bnd.bnd` of a project that uses the API:

```
-library: enableMessaging
```

Add `@RequireMessaging` (optionally `@RequireMessaging(protocol = "mqtt")`) to a class of your
bundle, so that the resolver pulls in a binding. Then add the bindings you need to the bndrun.

### 2.2 One configuration per instance

Each binding is a DS component with a factory PID and `configurationPolicy = REQUIRE`:

| Binding | Factory PID | `uri` |
|---|---|---|
| MQTT 5 | `org.gecko.messaging.mqtt` | `tcp://`, `ssl://`, `ws://`, `wss://` |
| MQTT 3.1.1 | `org.gecko.messaging.mqtt3` | as MQTT 5 |
| AMQP 0-9-1 | `org.gecko.messaging.amqp` | `amqp://user@host:5672/vhost`, `amqps://…` |
| WebSocket | `org.gecko.messaging.websocket` | `ws://host:port/base`, `wss://…` |
| In memory | `org.gecko.messaging.memory` | `memory://<broker>` (optional, default `default`) |
| WebSocket hub (server, not an instance) | `org.gecko.messaging.websocket.hub` | — |

With the Configurator:

```json
{
  ":configurator:resource-version": 1,
  "org.gecko.messaging.mqtt~sensors": {
    "osgi.messaging.name": "sensors",
    "uri": "ssl://broker.example:8883",
    "username": "device-17",
    ".password": "…",
    "tls.truststore": "/etc/app/broker-ca.p12",
    ".tls.truststore.password": "…"
  }
}
```

Keys starting with `.` are private: they never become service properties. Put passwords there.

### 2.3 Using the services

```java
import static org.gecko.messaging.api.MessagingConstants.*;

@Reference(target = "(osgi.messaging.name=sensors)")
MessagePublisher publisher;

@Reference(target = "(osgi.messaging.name=sensors)")
MessageSubscription subscription;

@Reference(target = "(osgi.messaging.name=sensors)", scope = ReferenceScope.PROTOTYPE_REQUIRED)
MessageContextBuilder builder;          // not thread safe: one per user
```

`publisher.publish(b -> …)` hands you a fresh builder, so for publishing alone you do not need
to reference the builder.

---

## 3 Configuration reference

Numbers may be given as numbers or as strings. All keys are optional except
`osgi.messaging.name` and, for network bindings, `uri`.

### 3.1 Identity and connection

| Key | Default | Change it when |
|---|---|---|
| `osgi.messaging.name` | required | — (unique per framework) |
| `uri` | per binding | always for network bindings |
| `username`, `.password` | — | the broker needs credentials. Over a plain scheme (`tcp`, `ws`, `amqp`) a warning is logged. |
| `clientId` | instance name | several frameworks connect with the same instance name |
| `connectTimeout` | `10000` ms | slow networks. Also bounds an MQTT subscribe and a WebSocket channel session. |
| `reconnectDelay` | `1000` ms | first backoff after a lost connection |
| `reconnectMaxDelay` | `30000` ms | largest backoff. A connection must hold this long before the backoff starts over. |

The instance reconnects by itself, with exponential backoff and jitter, and restores every
subscription before it reports `CONNECTED` (§10). Failed authentication or authorisation is
fatal: the instance goes to `CLOSED` and its streams fail.

### 3.2 TLS

TLS is used when `uri` has a TLS scheme. Without further keys the broker is checked against the
trust of the JVM.

| Key | Default | Purpose |
|---|---|---|
| `tls.truststore`, `.tls.truststore.password`, `tls.truststore.type` | trust of the JVM, `PKCS12` | broker certificate of a private CA |
| `tls.keystore`, `.tls.keystore.password`, `tls.keystore.type` | —, `PKCS12` | client certificate (mutual TLS) |

The host name is always checked. There is no key to turn that off or to trust every
certificate. A store that cannot be read fails activation. Any `tls.*` key on a URI without TLS
also fails activation (§2.2.1).

### 3.3 Threads, buffers and windows

| Key | Default | Meaning |
|---|---|---|
| `workerThreads` | `4` | threads that deliver streams and run handlers. `virtual` gives a virtual thread per task. |
| `bufferSize` | `1024` | messages per subscription buffer. It is also the in-flight limit of each reply-to handler and the upper bound of the receive window. |
| `bufferBytes` | `16777216` | payload bytes per subscription buffer |
| `queuePolicy` | `BLOCK` | full buffer: `BLOCK` waits, `DISCARD_OLDEST` drops the oldest, `FAIL` fails the stream |
| `publishWindow` | `100` | messages of stream publishes not yet handed over |
| `streamPublishFailures` | `stop` | a failed message of a stream publish ends it (`stop`) or is skipped and counted (`continue`) |

Receive window per binding: `receiveMaximum` (mqtt, default 100), `prefetch` (amqp, default
100). mqtt3 uses the broker's in-flight limit. Section 7 shows which values fit which scenario.

### 3.4 Publishing while disconnected

| Key | Default | Meaning |
|---|---|---|
| `publishAwaitConnection` | `0` (off) | ms a single publish waits for the connection instead of failing at once |
| `publishAwaitMessages` | `100` | messages that may wait at once |
| `publishAwaitBytes` | `1048576` | payload bytes that may wait at once |
| `streamPublishAwaitConnection` | `false` | a stream publish waits while disconnected instead of failing its messages |

### 3.5 Reply-to and sessions

| Key | Default | Meaning |
|---|---|---|
| `replyTimeout` | `30000` ms | how long a request waits for a response. Per request: extension `osgi.messaging.replyTo.timeout`. |
| `replyChannelPrefix` | `osgi/messaging/reply/<clientId>` | prefix of generated reply channels and of generated session channels |
| `replyToAllowed` | `osgi/messaging/reply/` and `<replyChannelPrefix>/` | responder side: reply-to prefixes it answers. `*` answers any. |
| `sessionChannelAllowed` | as `replyToAllowed` | requester side: prefixes a session responder may name for the requester's messages. `*` allows any. |
| `sessionLease` | `10000` ms | lease a requester asks for. Per session: extension `osgi.messaging.replyTo.sessionLease`. |
| `minSessionLease`, `maxSessionLease` | `1000`, `300000` ms | responder: the range it grants |
| `maxSessions` | `16` | responder: open sessions per handler |

Lists (`replyToAllowed`, `sessionChannelAllowed`) can be a comma-separated string, a `String[]`
or a collection. A prefix matches as written. End it with the separator (`app/replies/`) to allow
a subtree and nothing beside it (not `app/replies-admin`).

### 3.6 CloudEvents

| Key | Default | Meaning |
|---|---|---|
| `cloudEvents` | `none` | `binary` (attributes as protocol headers) or `structured` (JSON envelope) |
| `cloudEvents.acceptPlain` | `false` | also deliver received messages that are no CloudEvent |
| `cloudEvents.source` | `/osgi/messaging/<name>` | `source` of plain messages sent as events |
| `cloudEvents.type` | `org.gecko.messaging.message` | `type` of plain messages sent as events |

### 3.7 Default context

| Key | Meaning |
|---|---|
| `context.channel`, `context.channelExtension` | channel for `subscribe(null)` and messages without channel |
| `context.contentType`, `context.contentEncoding` | defaults of the default context |
| `context.extension.<key>` | extension `<key>` of the default context |

### 3.8 Keys per binding

| Binding | Key | Default | Meaning |
|---|---|---|---|
| mqtt | `cleanStart` | `true` | MQTT clean start |
| | `sessionExpiryInterval` | `0` | s; with `cleanStart=false` the broker keeps the session |
| | `keepAliveInterval` | `30` | s |
| | `receiveMaximum` | `100` | receive window, lowered to `bufferSize` |
| | `qos` | `1` | default QoS |
| | `maxMessageSize` | `1048576` | bytes, sent and received |
| | `sharedGroup` | `messaging` | group of shared subscriptions (queues) |
| mqtt3 | `cleanSession` | `true` | MQTT clean session |
| | `keepAliveInterval`, `qos`, `maxMessageSize`, `sharedGroup` | as mqtt | |
| | `maxInflight` | `100` | in-flight messages of the client |
| amqp | `exchange` | `amq.topic` | exchange of topics |
| | `heartbeat` | `30` | s |
| | `prefetch` | `100` | receive window per subscription, lowered to the buffer |
| | `qos` | `1` | `0` or `1` (publisher confirms) |
| | `maxMessageSize` | `1048576` | bytes |
| websocket | `keepAliveInterval` | `30` | s, idle timeout three times that |
| | `maxThreads` | `8` | client threads |
| | `maxMessageSize` | `1048576` | bytes |
| | `maxPublishSessions` | `16` | sessions kept for channels only published to |
| websocket hub | `host` | `localhost` | `0.0.0.0` for all interfaces |
| | `port` | `8090` | `0` for any free port |
| | `idleTimeout` | `120` | s |
| | `maxMessageSize` | `1048576` | bytes |
| | `maxThreads`, `maxConnections`, `maxOutgoingFrames` | `16`, `256`, `64` | bounds; a slower session is closed |
| | `allowedOrigins` | none | origins a browser page may connect from |

The hub has no authentication. Use it on trusted networks only.

### 3.9 Extensions per message, subscription or request

Set with `builder.extensionEntry(key, value)`.

| Extension | Where | Meaning |
|---|---|---|
| `osgi.messaging.qos` | publish, subscribe | 0, 1, 2 (AMQP 0, 1) |
| `osgi.messaging.timeToLive` | publish | ms until the message expires |
| `osgi.messaging.retain` | publish | broker keeps the last message of a topic |
| `osgi.messaging.channelType` | publish, subscribe | `topic` or `queue` |
| `osgi.messaging.autoCreate`, `osgi.messaging.durable` | publish, subscribe | create the channel, let it outlive the client (AMQP) |
| `osgi.messaging.bufferSize`, `bufferBytes`, `queuePolicy` | subscribe, request, session | this stream's own buffer, at most the instance's |
| `osgi.messaging.groupHeader` | subscribe, request, session | header marking the first message of a group (video key frame) |
| `osgi.messaging.replyTo.timeout` | request, session | reply timeout of this request |
| `osgi.messaging.replyTo.sessionLease` | session | lease asked for |
| `osgi.messaging.replyTo.sessionCredit` | session | credit granted to the responder (section 9.4) |
| `osgi.messaging.replyTo.sessionChannelAllowed` | session | additional allowed session channel prefixes (section 9.3) |

### 3.10 Service properties of reply-to handlers

Use `@MessagingTarget` and `@ReplyToSubscription`, or set the properties directly:

| Property | Annotation element | Meaning |
|---|---|---|
| `osgi.messaging.target` | `@MessagingTarget(value)` | filter selecting the instances (required) |
| `osgi.messaging.replyToSubscription.channel` | `channel` | request channel (required) |
| `osgi.messaging.replyToSubscription.channelExtension` | `channelExtension` | e.g. routing key |
| `osgi.messaging.replyToSubscription.channelType` | `channelType` | `queue` for competing handlers |
| `osgi.messaging.replyToSubscription.replyChannel` | `replyChannel` | where responses go when a request names none |
| `osgi.messaging.replyToSubscription.sessionChannelPrefix` | `sessionChannelPrefix` | session handlers: prefix of the channel the requester sends to |

---

## 4 Publishing

```java
publisher.publish(b -> b.channel("sensors/t1")
        .contentType("application/json")
        .header("unit", "C")
        .extensionEntry(EXTENSION_QOS, QOS_AT_LEAST_ONCE)
        .content(ByteBuffer.wrap(json))
        .buildMessage())
    .onFailure(t -> log.warn("publish failed", t));
```

The promise resolves when the message is handed over: confirmed by the broker for QoS ≥ 1,
written to the connection for QoS 0. Every failure goes through the promise, never as an
exception: an unsupported feature, a payload above `maxMessageSize`, or no connection.

**While not connected** a publish fails at once by default. If your code would only retry, set
`publishAwaitConnection` (e.g. `5000`). Then publishes wait, bounded by `publishAwaitMessages`
and `publishAwaitBytes`, and go out in their order after the reconnect (§6.2).

**A stream of messages.** `publisher.publish(pushStream)` sends a `PushStream<Message>` with
back-pressure. The delivering thread waits for room in the send window (`publishWindow`, or the
broker's limit), so a fast source never overruns the broker. The promise resolves with the
number of messages sent. Closing the stream stops it. With `streamPublishFailures=continue` a
failed message is skipped and counted instead of ending the stream.

**Relaying** from one channel or broker to another, throttled end to end:

```java
target.publish(source.subscribe("in/#").map(this::transform), contextOfTarget);
```

---

## 5 Subscribing

```java
PushStream<Message> stream = subscription.subscribe("sensors/+/temp");
stream.forEach(m -> handle(m.getContext().getChannel(), m.payload()));
// later: stream.close() ends the subscription
```

- The broker subscription is made when a terminal operation (`forEach`, …) connects. If the
  instance is connected, it is in place when that call returns.
- It survives reconnects until you close the stream. Connection changes are no stream events;
  use a `ConnectionListener` (section 12).
- Wildcards: `+`/`#` on MQTT and memory, `*`/`#` with `.` on AMQP, none on WebSocket.
- **Queues** (competing consumers): publisher and subscriber both set
  `EXTENSION_CHANNEL_TYPE = CHANNEL_TYPE_QUEUE`. On MQTT this is a shared subscription
  (`$share/<sharedGroup>/…`).
- A slow handler holds a worker thread. Put slow work on `workerThreads=virtual`, an instance of
  its own, or a buffer of your own behind the stream:
  `stream.buildBuffer().withExecutor(executor).withParallelism(4).build()`.

---

## 6 Acknowledgement

On instances with `acknowledge` (all except WebSocket):

```java
MessageContext ctx = builder.channel("orders")
        .extensionEntry(EXTENSION_CHANNEL_TYPE, CHANNEL_TYPE_QUEUE)
        .adapt(AcknowledgeMessageContextBuilder.class)
        .manualAcknowledge()
        .requeueOnReject(true)
        .messageContextBuilder()
        .buildContext();
subscription.subscribe(ctx).forEach(m -> {
    AcknowledgeHandler h = ((AcknowledgeMessageContext) m.getContext()).getAcknowledgeHandler();
    if (process(m)) h.acknowledge(); else h.reject();
});
```

| Mode | Use |
|---|---|
| automatic (default) | acknowledged when the stream takes the message |
| `acknowledgeFilter(predicate)` | refuse messages before they enter the stream |
| `acknowledgeFunction(consumer)` | decide with the `AcknowledgeHandler` before the stream |
| `manualAcknowledge()` | decide in the stream. Messages still open at close are rejected. |

A rejected message is re-queued only with `requeueOnReject(true)`, only on a queue, and at most
once. A second reject drops it, so a poison message cannot circle.

---

## 7 Buffers and flow control

Every subscription stream has a bounded buffer (`bufferSize`, `bufferBytes`, `queuePolicy`). The
broker's window (`receiveMaximum`, `prefetch`) decides how many unacknowledged messages it sends.
A message counts as acknowledged only when the stream has taken it from the buffer. So a slow
subscriber throttles the broker instead of blocking the protocol thread. The rule is:

> `bufferSize` ≥ receive window, and `bufferBytes` ≥ receive window × largest message.

The instance lowers a larger configured window to `bufferSize` by itself and logs it.

| Scenario | Settings |
|---|---|
| Edge device, low load | window 30, `bufferSize` 30, `bufferBytes` 4 MiB, `maxMessageSize` 128 KiB, `workerThreads` 2 |
| Default | window 100, `bufferSize` 1024, `bufferBytes` 16 MiB, `maxMessageSize` 1 MiB, `workerThreads` 4 |
| High throughput | window 1000, `bufferSize` 2048, `bufferBytes` 64 MiB, `maxMessageSize` 64 KiB, `workerThreads=virtual`, `publishWindow` 500 |
| Telemetry, loss acceptable | QoS 0, `queuePolicy=DISCARD_OLDEST` |
| Video next to commands | video subscription: small buffer, `DISCARD_OLDEST`, `groupHeader`; commands: instance defaults |
| One slow consumer among fast ones | an instance of its own (MQTT has one window per connection) |

A single subscription can choose a smaller buffer or another policy:

```java
MessageContext video = builder.channel("cam/1/video")
        .extensionEntry(EXTENSION_QOS, QOS_AT_MOST_ONCE)
        .extensionEntry(EXTENSION_BUFFER_SIZE, 8)
        .extensionEntry(EXTENSION_QUEUE_POLICY, "DISCARD_OLDEST")
        .extensionEntry(EXTENSION_GROUP_HEADER, "keyframe")   // header "keyframe=true" starts a group
        .buildContext();
```

With `groupHeader`, `DISCARD_OLDEST` drops whole groups, so nothing reaches the stream without
its key frame. `SubscriptionDTO.blockedMillis` and `droppedMessages` show a subscriber that
cannot keep up (section 12).

---

## 8 Request and response

### 8.1 One response

Requester:

```java
@Reference(target = "(osgi.messaging.name=backend)")
ReplyToPublisher replyTo;

Message request = builder.channel("calc")
        .extensionEntry(EXTENSION_CHANNEL_TYPE, CHANNEL_TYPE_QUEUE)
        .extensionEntry(EXTENSION_REPLY_TIMEOUT, 5_000L)
        .content(ByteBuffer.wrap(bytes))
        .buildMessage();
replyTo.publishWithReply(request)
    .onSuccess(response -> …)
    .onFailure(t -> …);         // TimeoutException, or MessagingException "The responder failed: …"
```

Reply channel and correlation id are generated if the request has none. Generated reply channels
start with `osgi/messaging/reply/`, which every responder accepts by default.

Responder:

```java
@Component
@MessagingTarget("(osgi.messaging.name=backend)")
@ReplyToSubscription(channel = "calc", channelType = "queue")
public class CalcHandler implements ReplyToSingleSubscriptionHandler {
    @Override
    public Message handleResponse(Message request, MessageContextBuilder response) {
        return response.content(compute(request.payload())).buildMessage();   // null: no response
    }
}
```

The builder is already set to the request's reply channel and correlation id. A handler that
throws is answered with `replyerror=The responder failed`. Exception text never leaves the
responder; it is logged there.

### 8.2 A stream of responses

The requester calls `replyTo.publishWithReplyMany(request)` and gets a `PushStream<Message>`. The
stream ends when the responder's stream ends (header `replycomplete`). It fails with
`MessagingException` when the responder fails, and with `TimeoutException` when nothing arrives
for the reply timeout. The response stream has its own buffer, set with the buffer extensions of
the request (section 7).

The responder implements `ReplyToManySubscriptionHandler`:

```java
public PushStream<Message> handleResponses(Message request, Supplier<MessageContextBuilder> responses) {
    return rows(request).map(row -> responses.get().content(encode(row)).buildMessage());
}
```

The stream is sent with back-pressure, as a stream publish (section 4).

### 8.3 Which reply channels are answered

A request names its reply channel itself. A responder answers only reply channels that start with
a `replyToAllowed` prefix. By default that is `osgi/messaging/reply/` and the subtree of its own
`replyChannelPrefix`. Other requests are not handled at all and are counted in
`ReplyToSubscriptionDTO.refusedRequests`. If callers use fixed reply channels (`calc.replies`),
list them on the responder: `replyToAllowed = "calc.replies"`. A handler's own `replyChannel`
property is not checked; you configured it yourself.

---

## 9 Duplex sessions

A session joins a stream from a responder to a requester with messages from the requester to the
responder, for as long as both want. The typical case is a camera that streams video to a viewer,
and the viewer steers the camera with commands. Sessions need the feature `replyToSession`
(memory, mqtt, amqp; mqtt3 and websocket with `cloudEvents=structured`).

### 9.1 Basic use

Requester:

```java
replyTo.openSession(builder.channel("cam/1/session").content(params).buildMessage())
    .onSuccess(session -> {
        session.receive().forEach(frame -> show(frame));    // connecting receive() starts the stream
        session.send(command("zoom", 2));                    // Promise<Void>
        session.closed().onFailure(t -> log.info("session ended", t));
    });
// session.close() ends it from this side
```

Responder:

```java
@Component
@MessagingTarget("(osgi.messaging.name=cameras)")
@ReplyToSubscription(channel = "cam/1/session")
public class CameraSession implements ReplyToSessionHandler {
    @Override
    public PushStream<Message> handleSession(Message open, PushStream<Message> commands,
            Supplier<MessageContextBuilder> responses) {
        commands.forEach(this::steer);                       // the requester's send(...)
        return frames().map(f -> responses.get().content(f).buildMessage());
    }
}
```

How a session runs:

1. `openSession` subscribes a reply channel R and sends `open` to the handler's channel. The
   payload and headers of the open message reach the handler as `open`.
2. The responder checks R (`replyToAllowed`, as for requests) and `maxSessions`, takes a channel
   C for the requester's messages and answers `accept`. The requester checks C (section 9.3). The
   promise of `openSession` resolves.
3. When you connect `receive()`, the requester sends `ready`. Only then the responder starts the
   handler's stream. Nothing is sent before the requester listens. `receive()` returns the same
   stream on every call.
4. Both sides send a `ping` when they have sent nothing for a third of the lease. A side that
   hears nothing for the lease ends the session with `TimeoutException`.
5. `close()` on either side ends the session. The end of the handler's stream ends it too.

| Event | Requester sees |
|---|---|
| handler's stream ends | `receive()` completes, `closed()` resolves |
| handler's stream fails | `receive()` and `closed()` fail with `MessagingException` "The responder failed" (no details) |
| handler ends with a reason (9.5) | the last message, then `SessionEndedException` with the reason |
| `maxSessions` reached | `openSession` fails with `MessagingException` |
| reply channel not allowed by the responder | no answer: `openSession` fails after the reply timeout |
| lease runs out | `TimeoutException` |

### 9.2 Lease and limits

The requester asks for `sessionLease` (or the extension `osgi.messaging.replyTo.sessionLease`).
The responder keeps it between `minSessionLease` and `maxSessionLease`. A short lease detects a
lost partner sooner but costs more pings. The responder accepts at most `maxSessions` sessions per
handler. Messages the requester sends before the handler connects its `commands` stream are held,
at most `bufferSize`.

### 9.3 Which channels a session uses

There are two channels:

- **R**, the requester's reply channel. The responder sends the stream there. By default R is
  generated (`<replyChannelPrefix>/<uuid>`). If the `openSession` context names a reply-to
  channel, that one is used. The responder answers only if R is allowed by its `replyToAllowed`.
- **C**, the channel the requester's messages go to. The responder chooses it. With the handler
  property `sessionChannelPrefix` it is `<prefix>/<uuid>`, otherwise a generated channel of the
  responder instance. The requester sends there only if C starts with a prefix of its
  `sessionChannelAllowed`, or of the extension `osgi.messaging.replyTo.sessionChannelAllowed` of
  the `openSession` context. Otherwise the session fails with `MessagingException`, and nothing
  goes to C, not even `close`. This protects the requester from a forged `accept` that would
  direct its messages to any channel it may write to.

With all defaults, both channels lie under `osgi/messaging/reply/` and every check passes. You
only configure something when you choose the channels yourself.

**Example: a broker ACL per node.** Mosquitto lets each node read only its own subtree, e.g.
`nodes/<node>/…` (with `use_identity_as_username`). The viewer `node-7` opens a session with the
camera `cam-1`:

Responder instance `cameras` on cam-1 and its handler:

```json
"org.gecko.messaging.mqtt~cameras": {
  "osgi.messaging.name": "cameras",
  "uri": "ssl://broker:8883",
  "replyToAllowed": "nodes/"
}
```

```java
@ReplyToSubscription(channel = "nodes/cam-1/session", sessionChannelPrefix = "nodes/cam-1/in")
```

Requester on node-7:

```java
Message open = builder.channel("nodes/cam-1/session")
        .replyTo("nodes/node-7/video")                                  // R in its own subtree
        .extensionEntry(EXTENSION_SESSION_CHANNEL_ALLOWED, "nodes/cam-1/in/")   // allow C
        .content(params)
        .buildMessage();
replyTo.openSession(open);
```

Instead of the extension, the requester instance can allow the prefix for all sessions:
`sessionChannelAllowed = "nodes/"`. The extension adds to the instance's list, it does not
replace it. Several sessions may share one named R; each takes only the messages with its own id.

### 9.4 Credit: a slow requester slows the responder down

Without credit, a responder sends as fast as its handler produces. A slow requester then fills
its receive buffer and, with `BLOCK`, holds up its connection. With credit, the requester tells
the responder how many messages it may send:

- `ready` carries the credit n. Each time the application has taken n/2 messages from
  `receive()`, the requester grants as many again.
- The responder sends only within the credit. Otherwise it holds the handler's stream in the
  delivering thread, just as a stream publish waits for its window.

| Receive buffer of the session (`queuePolicy`) | Default credit |
|---|---|
| `BLOCK`, `FAIL` | the buffer size: the responder never sends more than fits |
| `DISCARD_OLDEST` | `0`, no credit: the responder sends freely, the viewer skips frames |

Set it with the extension `osgi.messaging.replyTo.sessionCredit` of the `openSession` context.
`0` turns credit off, and a value above the buffer size is lowered to it. The default fits nearly
every case: video viewers want the newest frame (no credit), data consumers want everything
(credit). The last message of a session ended with a reason (9.5) is sent outside the credit.

A video viewer:

```java
Message open = builder.channel("cam/1/session")
        .extensionEntry(EXTENSION_BUFFER_SIZE, 8)
        .extensionEntry(EXTENSION_QUEUE_POLICY, "DISCARD_OLDEST")
        .extensionEntry(EXTENSION_GROUP_HEADER, "keyframe")
        .content(params)
        .buildMessage();
```

### 9.5 Ending a session with a reason

A handler that wants to tell the requester *why* it ends lets its stream fail with
`SessionEndedException`:

```java
return frames().map(f -> {
    Message m = responses.get().content(f.data()).buildMessage();
    if (f.isLast()) {
        throw new SessionEndedException("camera switched off", m);   // m goes out as the last message
    }
    return m;
});
```

`SessionEndedException` is a `MessagingException` and so unchecked. It ends the session from
anywhere in the handler's pipeline, also as the failure of a stream built elsewhere, as long as
it is the failure or one of its causes.

The responder sends the last message (or an empty one) with the reason as an ordinary session
message, then ends the session. The requester's `receive()` delivers that message as its last
element and then fails with `SessionEndedException`. `getReason()` is the reason, cut to 64
characters without control characters, and `getLastMessage()` is that message. `closed()` fails
with the same exception.

Any other failure of the handler's stream reaches the requester only as "The responder failed",
without details. Use `SessionEndedException` for reasons the requester may see, and plain
exceptions for internals.

### 9.6 Monitoring sessions

`ReplyToSubscriptionDTO.session` marks a session handler, and `openSessions` counts its open
sessions (section 12).

---

## 10 Flow.Publisher

`org.gecko.messaging.api.util.Flows` adapts between `PushStream` and
`java.util.concurrent.Flow.Publisher`, for code that speaks Flow (Reactive Streams, Mutiny,
Reactor via `FlowAdapters`, `SubmissionPublisher`):

```java
Flow.Publisher<Message> frames = Flows.toPublisher(session.receive());       // to Flow
PushStream<Message> out = Flows.fromPublisher(mySubmissionPublisher);        // from Flow
```

- `toPublisher(stream[, executor])` takes one subscriber. The stream is consumed on a thread of
  its own (a virtual thread by default), which waits while the subscriber has requested nothing.
  `cancel()` closes the stream, and so ends a subscription or session.
- `fromPublisher([provider,] publisher)` requests one element at a time on the publisher's
  threads. Closing the stream cancels the subscription. A session handler may return
  `fromPublisher(...)` from `handleSession`: when the session ends, the source is cancelled.

---

## 11 CloudEvents

Set `cloudEvents=binary` (memory, mqtt, amqp) or `cloudEvents=structured` (all bindings). Then:

```java
publisher.publish(b -> b.adapt(CloudEventMessageContextBuilder.class)
        .type("com.example.temperature")
        .source("/sensors/t1")
        .messageContextBuilder()
        .channel("sensors/t1")
        .contentType("application/json")
        .content(ByteBuffer.wrap(json))
        .buildMessage());
```

`id` and `time` are generated if missing. Received events have a `CloudEventMessageContext`.
Plain messages you publish are sent as events with `cloudEvents.source` and `cloudEvents.type`.
An instance with CloudEvents takes only CloudEvents. Other received messages are dropped and
counted, unless `cloudEvents.acceptPlain=true`.

`structured` also carries content type, headers, correlation id and reply-to in the envelope.
That is how mqtt3 and websocket get request/response and sessions.

---

## 12 Connection state and monitoring

**Connection events.** Register a `ConnectionListener` service. With `@MessagingTarget` it hears
the selected instances, without it all of them. It is told the current state when it registers.

```java
@Component
@MessagingTarget("(osgi.messaging.name=sensors)")
public class Resync implements ConnectionListener {
    public void connectionChanged(ConnectionEvent e) {
        if (e.state() == ConnectionState.CONNECTED && e.reconnect() && !e.sessionPresent()) {
            resynchronise();          // messages published in between may be lost
        }
    }
}
```

**Runtime DTO.** `MessageServiceRuntime.getRuntimeDTO()`:

| Field | Shows |
|---|---|
| `connectionState`, `reconnects` | connection health |
| `receiveWindow` | effective broker window |
| `subscriptions[]` | per subscription: `receivedMessages`, `droppedMessages`, `bufferedMessages`, `bufferedBytes`, `blockedMillis` |
| `replyStreams[]` | response streams of `publishWithReplyMany` open now |
| `replyToSubscriptions[]` | handlers: `handledRequests`, `refusedRequests`, `session`, `openSessions` |
| `streamPublishFailures`, `awaitingPublishes` | skipped stream messages, publishes waiting for the connection |

A growing `blockedMillis` means a subscriber is too slow for its buffer. Growing
`droppedMessages` means discarded messages (`DISCARD_OLDEST`), undecodable messages, or messages
that are no CloudEvents. Growing `refusedRequests` means requests whose reply channel the
responder does not allow (section 8.3).

---

## 13 Security defaults and how to open them

Everything is safe without configuration. Each row says what you give up when you open it.

| Default | Open it with | Risk when opened |
|---|---|---|
| Responses only to `osgi/messaging/reply/` and the own reply prefix | `replyToAllowed` prefixes, `*` for any | a sender directs responses and handler side effects to channels the responder may write to |
| Session messages only to the same channels | `sessionChannelAllowed`, extension per session, `*` for any | a forged `accept` directs the requester's messages anywhere |
| Remote failures carry no details | — (use `SessionEndedException` for reasons meant for the other side) | — |
| CloudEvents instances take CloudEvents only | `cloudEvents.acceptPlain=true` | messages without attributes reach code that expects them |
| Bounded buffers, sizes, sessions, in-flight requests | raise `bufferSize`, `bufferBytes`, `maxMessageSize`, `maxSessions` | memory use a remote party controls |
| A message re-queued at most once, only on queues | — (fixed) | — |
| Broker certificate and host name always checked | `tls.truststore` for a private CA | — (no switch to turn it off) |
| Passwords never in service properties, DTOs or logs | use `.password`, `.tls.*.password` | — |
| Warning on credentials over a plain scheme | use `ssl://`, `wss://`, `amqps://` | — |
| WebSocket hub on `localhost`, `Origin` checked | `host`, `allowedOrigins` | the hub has no authentication |

---

## 14 Troubleshooting

| Symptom | Cause and remedy |
|---|---|
| `FeatureNotSupportedException` naming a feature | The instance does not announce it, see §13.1. Use another binding, or `cloudEvents=structured` for headers, reply-to and sessions on mqtt3 and websocket. |
| Publish fails with `MessagingException` while starting | Not connected yet. Wait for `CONNECTED` (`ConnectionListener`) or set `publishAwaitConnection`. |
| Requests time out, the responder logs nothing (only at FINE) | The reply channel is not allowed: `refusedRequests` grows. Add its prefix to `replyToAllowed` on the responder. |
| `openSession` times out | As above for the reply channel R, or no handler bound on the channel (check `osgi.messaging.target`). |
| Session fails with "named a channel that is not allowed" | The responder's C is not in the requester's `sessionChannelAllowed`. Add the handler's `sessionChannelPrefix` (with `/`) there or as the extension. |
| `MessagingException` "The responder failed" | The handler threw. The details are in the responder's log. |
| Subscriber far behind, `blockedMillis` grows | Handler too slow: `workerThreads=virtual`, an own buffer with parallelism, or an instance of its own. |
| Messages lost under load | `DISCARD_OLDEST` or QoS 0 chosen. For lossless delivery use QoS 1 and `BLOCK`. |
| INFO "receiveMaximum … lowered to bufferSize" (or `prefetch`) | The window was above `bufferSize`. This is intended; raise `bufferSize` if you want the larger window. |
| Instance goes to `CLOSED` at once | Authentication or authorisation refused (fatal). Check credentials and ACL. |
| Activation fails naming a store path | TLS store missing, wrong password or type, or `tls.*` on a URI without TLS. |
