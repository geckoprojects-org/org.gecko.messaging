# Migrating from Gecko Messaging 1.x to 2.0

This guide compares the old Gecko messaging adapters (1.x, package `org.gecko.osgi.messaging`
with the adapter bundles `org.gecko.adapter.*`) with Gecko Messaging 2.0 (API
`org.gecko.messaging.api`, bindings `org.gecko.messaging.*`). It explains what a consumer has to
change.

## 1. Summary

- **New packages and services.** `org.gecko.osgi.messaging.MessagingService` is split into
  `MessagePublisher` (publish) and `MessageSubscription` (subscribe). Reply-to goes through
  `ReplyToPublisher`. Every instance also registers a `MessageContextBuilder` (prototype scope)
  and a `MessageServiceRuntime`. The old `MessagingRPCService`, `MessagingReplyToService` and
  `MessagingRPCPubOnSub` are gone.
- **One instance per factory configuration.** 1.x had a separate component (and PID) for each
  role: `AMQPService`, `AMQPRPCService`, `AMQPReplyToService`, `AMQPRPCRequestService`,
  `AMQPubOnSubService`, `AMQPReplyToExecutor`, `AMQPRPCWorkerService`, `MQTTService`,
  `MQTTRPCService` and the others. In 2.0 one factory configuration of `org.gecko.messaging.amqp`,
  `.mqtt`, `.mqtt3`, `.websocket` or `.memory` gives one connection and all of the services above.
  You select an instance with `(osgi.messaging.name=<name>)`.
- **A protocol-neutral context.** In 1.x you had to cast to `AMQPContext` or `MQTTContext`, and
  options the adapter did not know were dropped without a word. In 2.0 the
  `MessageContextBuilder` sets channel, extension, content type, headers and correlation id.
  Protocol options are standard extensions such as `osgi.messaging.qos`,
  `osgi.messaging.retain`, `osgi.messaging.timeToLive` and `osgi.messaging.channelType`.
- **Features are announced and enforced.** Each instance lists what it supports in the service
  property `osgi.messaging.feature`. If you use a feature that is not announced, the call fails
  with a `FeatureNotSupportedException`. It is not ignored any more.
- **Publishing is asynchronous.** `publish` returns `Promise<Void>`. It no longer throws or returns
  `void`. At QoS ≥ 1 the promise resolves when the broker has confirmed the message.
- **The instance owns reconnecting.** It restores subscriptions after a reconnect, reports
  connection state through `ConnectionListener` whiteboard services, and shows its state as DTOs
  through `MessageServiceRuntime`. JMX beans are gone.
- **Reply-to responders are whiteboard services** (`ReplyToSingleSubscriptionHandler`,
  `ReplyToManySubscriptionHandler`). They replace `WorkerFunction`, `ma.posFunction` and
  `BasicReSubscribeConsumerFactory`.
- **Secure defaults.** A rejected message is re-queued only on queues and at most once. An
  instance answers only reply channels that start with `osgi/messaging/reply/` or with its own
  `replyChannelPrefix`, unless `replyToAllowed` is configured (`*` allows any channel). Message
  size is limited to 1 MiB by default on mqtt, mqtt3, amqp and websocket. Wildcards on a binding
  without the `wildcard` feature fail; they are no longer taken literally. The WebSocket hub
  checks the `Origin` header. Plain-text schemes that carry credentials log a warning once.
  CloudEvents are optional (`cloudEvents=binary|structured`).
- **Responders can fail explicitly.** A response with the header `replyerror` fails the caller's
  promise or ends its stream of responses with an error.

## 2. Feature matrix

Legend: **yes**, **no**, **partial** (see the footnote), **n/a** (does not apply). For 2.0 the
cells follow the binding's `FEATURES` list. "+CE" means that structured CloudEvents
(`cloudEvents=structured`) add the feature through the JSON envelope. The envelope always adds
`contentType`, `headers`, `replyTo`, `replyToMany`, `replyToSubscription`,
`generateCorrelationId` and `generateReplyChannel` (`STRUCTURED_ENVELOPE_FEATURES` in
`AbstractMessagingInstance`).

| Capability | old AMQP | old MQTT v3 | old MQTT v5 | old EventAdmin | memory | mqtt3 | mqtt (v5) | amqp | websocket |
|---|---|---|---|---|---|---|---|---|---|
| publish/subscribe | yes | yes | yes | yes | yes | yes | yes | yes | yes |
| point-to-point / queue | yes | no | no | no | yes | yes¹ | yes¹ | yes | no |
| wildcards | yes² | yes³ | yes³ | partial⁴ | yes | yes | yes | yes | no (refused) |
| channel extension / routing key | yes | no | no | no | yes | no | no | yes | no |
| QoS 0/1/2 | partial⁵ | yes | yes | no | yes | yes | yes | partial⁶ | no |
| retain | no | yes | yes | no | yes | yes | yes | no | no |
| TTL | yes⁷ | no | no | no | yes | no | yes | yes | no |
| headers / properties | partial⁸ | no | no | yes | yes | no (+CE) | yes | yes | no (+CE) |
| content type / encoding | partial⁹ | no | no | yes | yes | no (+CE) | yes | yes | no (+CE) |
| correlation id | yes | no | no | yes¹⁰ | yes | no (+CE) | yes | yes | no (+CE) |
| reply-to single | yes | partial¹¹ | partial¹¹ | no | yes | no (+CE) | yes | yes | no (+CE) |
| reply-to many / streaming | yes | no | no | no | yes | no (+CE) | yes | yes | no (+CE) |
| reply-to whiteboard / RPC handler | partial¹² | no | no | no | yes | no (+CE) | yes | yes | no (+CE) |
| manual ack / reject | yes | no | no | no | yes | yes | yes | yes | no |
| re-queue on reject | no¹³ | no | no | no | yes | partial¹⁴ | partial¹⁴ | yes¹⁵ | no |
| connection state + listener | no | no | no | n/a | yes | yes | yes | yes | yes |
| runtime DTO | partial¹⁶ | no | no | no | yes | yes | yes | yes | yes |
| reconnect with subscription restore | partial¹⁷ | partial¹⁸ | partial¹⁸ | n/a | n/a | yes | yes | yes | yes |
| CloudEvents | no | no | no | no | yes (binary, structured) | structured only | yes (binary, structured) | yes (binary, structured) | structured only |
| size limits | no | no | no | no | no | yes¹⁹ | yes¹⁹ | yes²⁰ | yes²¹ |
| TLS | partial²² | partial²³ | partial²³ | n/a | n/a | yes²⁴ | yes²⁴ | yes²⁴ | yes²⁴ |

Footnotes:

1. Shared subscriptions `$share/<group>/<topic>`, with the group set by `sharedGroup` (default:
   `messaging`, so every instance on a queue competes for its messages, as receivers of a queue
   should; different queues stay apart). The old MQTT adapters had no
   queues. MQTT 3.1.1 does not define them, but Mosquitto, HiveMQ and EMQX offer
   them.
2. Only through an exchange subscription with a `topic` routing type (`AMQPContextBuilder.topic()`
   plus `exchange(name, routingKey)`).
3. `+` and `#` work. `AbstractMqttService.subscribe` also rewrites `*` to `#`
   (`AbstractMqttService.java:97`).
4. Only the EventAdmin topic wildcard (`a/b/*`). It is passed as `EVENT_TOPIC`.
5. QoS cannot be selected. There are only `deliveryMode`, the `amqp.basicQos` prefetch and
   `amqp.autoAcknowledge`. There are no publisher confirms.
6. QoS 0 and 1 work, and QoS 1 waits for the publisher confirm. QoS 2 is refused as an
   unsupported feature before anything is sent.
7. `AMQPContextBuilder.expiration(String)`, mapped to the AMQP `expiration` property.
8. Headers are sent (`AMQPContext.getHeader()` → `BasicProperties.headers`,
   `AMQPHelper.java:112`). They are not exposed on receipt: `AMQPAcknowledgingConsumer.handleDelivery`
   copies no headers.
9. Both are sent. On receipt only `contentType` is copied (`AMQPAcknowledgingConsumer.java:110`).
10. Passed through as the event property `correlation.id`. There is no reply-to service.
11. `MqttRPCService.publishRPC` subscribes to the reply topic and then publishes. It has no
    correlation id and no timeout, and the default reply topic is the request topic (see section 3).
12. `AMQPubOnSubService` (`WorkerFunction`), `AMQPReplyToExecutor` (`ma.posFunction`) and
    `AMQPRPCWorkerService` (`rpc.workerFunction`) exist, but they have defects (see section 3).
13. `AMQPAcknowledgingConsumer.rejectMessage` always calls `basicReject(deliveryTag, false)`
    (`AMQPAcknowledgingConsumer.java:142`).
14. Only on queues (shared subscriptions), at most once. MQTT has no broker-side re-queue: a
    re-queued message is published again unchanged and the original is acknowledged.
15. Only on queues, at most once, based on the broker's `redelivered` flag
    (`basicNack(tag, false, requeue && !redelivered)`).
16. JMX only, and only for `AMQPService` with `jmx=true`: `AMQPServiceMetricMBean` and
    `AMQPConsumerMetricMBean`.
17. Recovery is left to the RabbitMQ client's automatic recovery. `autoRecovery=false` cannot
    switch it off, because `AMQPService` only calls `setAutomaticRecoveryEnabled` when the value
    is `true` (`AMQPService.java:277`). `BasicAMQPService` ignores the key completely
    (`BasicAMQPService.java:179-181`, commented out). No state is exposed.
18. A 5 s `Timer` creates a new client and subscribes again. Paho's automatic reconnect is also on,
    so both mechanisms run (see section 3).
19. `maxMessageSize`, default 1 MiB. It applies to packets received and to messages sent.
20. `maxMessageSize`, default 1 MiB. It is checked before sending, announced as
    `osgi.messaging.maxMessageSize`, and used as the client's maximum inbound body size (the
    amqp-client default was 64 MiB).
21. `maxMessageSize` (default 1 MiB) is set on the Jetty client. The hub has its own
    `maxMessageSize`, `maxConnections` and `maxOutgoingFrames`.
22. Only components based on `BasicAMQPService` (`AMQPRPCRequestService`, `AMQPRPCWorkerService`)
    have `tls`, `keyStore` and `trustStore`. `AMQPService` and the other old components accept only
    `brokerUrl` values that start with `amqp://`. They fall back to plain host/port otherwise
    (`AMQPService.java:244`).
23. An `ssl://` broker URL with Paho's default socket factory, which uses the JVM's default TLS
    settings. There are no keystore keys.
24. Through the URI scheme. There are no keystore or truststore keys: TLS uses the JVM defaults
    (`javax.net.ssl.*` system properties). `amqps://`: `ConnectionFactory.setUri` enables TLS with
    `SSLContext.getDefault()` and hostname verification. `wss://`: the Jetty client with endpoint
    identification. `ssl://`: Paho's default with hostname verification. A warning is logged once
    when credentials go over a plain-text scheme (`tcp`, `mqtt`, `ws`, `amqp`).

## 3. Defects of 1.x fixed by design in 2.0

These were confirmed by reading the 1.x sources. Paths are relative to `old/`.

| # | Defect (1.x) | Where | How 2.0 avoids it |
|---|---|---|---|
| D1 | With Paho automatic reconnect on (`setAutomaticReconnect(true)`) and a clean session, Paho reconnects but does not subscribe again. The adapter's own timer only acts if the client is still disconnected after 5 s, so a quick Paho reconnect leaves the stream silent | `PahoV3Client.java:140`, `PahoV5Client.java:139`, `MqttPushEventSource.java:126` | Driver auto-reconnect is off. The instance is the only reconnect owner and restores every subscription |
| D2 | Closing a subscription does not close its dedicated Paho client and does not cancel its `Timer`. Event sources are never removed from the map | `MqttPushEventSource.java:73-75`, `AbstractMqttService.java:101` (no `remove`) | Subscriptions are instance state and end when the stream closes. `stop()` releases all threads |
| D3 | Thread pools are created per client and never shut down: `newCachedThreadPool` and `newScheduledThreadPool` | `PahoV3Client.java:59,78`, `PahoV5Client.java:62,79` | One scheduler thread plus `workerThreads` per instance, released on `stop()` |
| D4 | Executors are never shut down, and one is created for every failed request | `BasicAMQPService.java:86`, `AMQPubOnSubService.java:79`, `AMQPReplyToExecutor.java:95`, `AMQPRPCRequestService.java:127`, `BasicReSubscriber.java:43` (replaced without shutdown by `BasicRPCWorkerService.java:42`) | Same as D3 |
| D5 | The MQTT RPC services have no `@Deactivate`, so their client is never closed | `AbstractMqttRPCService.java:49` (`close()` is never called), `MqttRPCService` (v3 and v5) | Reply-to belongs to the instance and its lifecycle |
| D6 | MQTT RPC: the default reply topic is the request topic, so the caller can receive its own request. There is no correlation id and no timeout, and the reply subscription is never removed | `AbstractMqttRPCService.java:70,94-95`, `PahoV3Client.java:172` | `ReplyToPublisher` subscribes before publishing, matches the correlation id, times out after `replyTimeout` and unsubscribes |
| D7 | AMQP RPC promises have no timeout. The per-request channel is closed only on success | `AMQPRPCService.java:153-164`, `AMQPReplyToService.java:156-167`, `AMQPRPCRequestService.java:118-125` | Same as D6 |
| D8 | `AMQPRPCRequestService.publishRPC` ignores the context the caller passes and builds one from the configuration | `AMQPRPCRequestService.java:96-99` | The context given to `publishWithReply(message, context)` is used |
| D9 | A channel is created before the cache is checked, so a channel leaks whenever one is already cached | `BasicAMQPService.java:342,347-349` and `396,398-400` | One connection per instance, with channels managed by the binding |
| D10 | Inverted check: `connectQueue` throws exactly when the queue context is valid | `BasicAMQPService.java:393` | n/a (rewritten) |
| D11 | `doConnect` returns the old (on the first call, `null`) connection instead of the new one | `BasicAMQPService.java:222-228` | Connect has a deadline and a defined result |
| D12 | TLS keystore and truststore streams are never closed | `BasicAMQPService.java:201,208` | No keystore handling in the binding |
| D13 | Responders consume from the wrong queue in exchange mode: they bind a server-named queue but call `basicConsume` on the configured name. `AMQPubOnSubService` validates `topic` but uses `exchange` as the queue name | `AMQPReplyToExecutor.java:181,190`, `AMQPubOnSubService.java:166-175,195` | The whiteboard subscribes to `osgi.messaging.replyToSubscription.channel` |
| D14 | Responders open a `Connection` and never close it, because deactivate closes only the channel. Activation purges the request queue | `AMQPReplyToExecutor.java:169,185,194`, `AMQPubOnSubService.java:153,170,179` | Reconnect and close are owned by the instance |
| D15 | Responders reply to any `replyTo` a request names (migration impact: see 4.6) | `AMQPPubOnSubConsumer.java:115`, `AMQPPubOnSubManyConsumer.java:167` | Only reply channels that start with `osgi/messaging/reply/` (or `replyToAllowed`) are answered. Refusals are counted in `ReplyToSubscriptionDTO.refusedRequests` |
| D16 | MBeans are registered but never unregistered (no `unregisterMBean` anywhere in the sources) | `AMQPService.java:215,405` | No JMX. `MessageServiceRuntime` DTOs are snapshots |
| D17 | NPE when the channel is not open and no consumer exists yet | `AMQPService.java:222-223` | — |
| D18 | `publish` silently does nothing for a non-`AMQPContext` context or a closed channel | `AMQPService.java:158,176` | Unsupported options throw `FeatureNotSupportedException`. A failed publish fails the promise |
| D19 | `content.array()` ignores position and limit, and fails for direct or read-only buffers | `AMQPService.java:177`, `AbstractMqttService.java:137`, `AbstractMqttRPCService.java:95` | 2.0 copies only the remaining bytes, from a duplicate (`SimpleMessage.java:90,99-100`) |
| D20 | The body of every received message is logged at INFO, or built eagerly at FINE | `AMQPService.java:447`, `AMQPRPCService.java:313`, `AMQPReplyToConsumer.java:87`, `AMQPAcknowledgingConsumer.java:104` | — |
| D21 | A rejected message is never re-queued | `AMQPAcknowledgingConsumer.java:142` | `requeueOnReject(true)`, at most once |
| D22 | The first `publish` connects synchronously on the caller thread, and the lazy client creation is not synchronized | `AbstractMqttService.java:118-126`, `PahoV3Client.java:83` | The connection is established by the instance, with `connectTimeout` |
| D23 | `ensureOpenConnection` blocks the caller on the connection promise | `BasicAMQPService.java:233` | `publish` is asynchronous |
| D24 | `ReplyToPolicy.getPolicy` throws an NPE when `replyToPolicy` is missing | `ReplyToPolicy.java:28` | Many responses end with the header `replycomplete` |
| D25 | `PROP_RPC_EXCHANGE` has the same value as `PROP_RPC_QUEUE` (`"rpcQueue"`) | `MessagingConstants.java:27-28` | Removed |
| D26 | The v5 client ignores `maxInflight` and uses no MQTT 5 properties (content type, correlation data, response topic, user properties, expiry) | `PahoV5Client.java:125-141,233-238` | The `mqtt` binding maps them natively |

## 4. Migration

### 4.1 Build setup (bnd)

In the workspace `cnf/build.bnd`:

```
-library: geckoMessaging
```

This adds a repository with the messaging bundles and their runtime dependencies (Paho, the
RabbitMQ client with Netty, Jetty, OSGi promises and push streams). It needs bnd 7.2.0 or later
(`-require-bnd`). The 1.x workspace library had the same name, `geckoMessaging`, so the line may
already be there; check that it now resolves to 2.0.

In each project's `bnd.bnd`:

```
-library: enableMessaging
```

This puts `org.gecko.messaging.api`, `org.osgi.util.promise`, `org.osgi.util.pushstream` and
`org.osgi.util.function` on the build path. Remove `org.gecko.osgi.messaging`,
`org.gecko.adapter.*` and `org.gecko.util.pushstream` from `-buildpath`. Choose the binding
bundles in the bndrun.

### 4.2 Mapping table

| Old (1.x) | New (2.0) |
|---|---|
| package `org.gecko.osgi.messaging` | `org.gecko.messaging.api` (+ `.acknowledge`, `.replyto`, `.runtime`, `.cloudevents`, `.dto`, `.propertytypes`, `.annotations`) |
| `MessagingService.publish(topic, buffer[, ctx])` | `MessagePublisher.publish(Message[, String channel \| MessageContext])` or `publish(Function<MessageContextBuilder, Message>)` → `Promise<Void>` |
| `MessagingService.subscribe(topic[, ctx])` | `MessageSubscription.subscribe(String channel)` / `subscribe(MessageContext)` → `PushStream<Message>` |
| `MessagingRPCService.publishRPC` | `ReplyToPublisher.publishWithReply` → `Promise<Message>` |
| `MessagingReplyToService.publishSingle` / `publishMany` | `ReplyToPublisher.publishWithReply` / `publishWithReplyMany` |
| `MessagingRPCPubOnSub` (`AMQPReplyToExecutor`, `ma.posFunction`) | `ReplyToManySubscriptionHandler` whiteboard service |
| `WorkerFunction` (`AMQPubOnSubService`, `AMQPRPCWorkerService`) | `ReplyToSingleSubscriptionHandler` whiteboard service |
| `MessagingResubscribingPubOnSub.resubscribe`, `BasicReSubscribeConsumerFactory`, `ReSubscribeCallback` | none. Restoring subscriptions is the instance's job |
| `Message.topic()` | `message.getContext().getChannel()` |
| `Message.payload()`, `Message.getContext()` | unchanged names (`payload()`, `getContext()`) |
| `MessagingContext.getQueueName()` / `getRoutingKey()` | `MessageContext.getChannel()` / `getChannelExtension()` |
| `MessagingContext.getReplyAddress()` | `MessageContext.getReplyToChannel()` |
| `getContentType()`, `getContentEncoding()`, `getCorrelationId()` | same names on `MessageContext` |
| `MessagingContext.getSoure()` / `getId()` | `CloudEventMessageContextBuilder.source(..)` / `id(..)` (with `cloudEvents` on) |
| `ReplyToPolicy` / `PROP_REPLY_TO_POLICY` | none. Choose `publishWithReply` or `publishWithReplyMany`. The end of a stream is header `replycomplete` (`HEADER_REPLY_COMPLETE`) |
| `new AMQPContextBuilder()` / `new MQTTContextBuilder()` / `SimpleMessagingContextBuilder` | the `MessageContextBuilder` service (prototype scope), or the function passed to `publish(Function)` |
| `.queue(name)` | `.channel(name).extensionEntry(EXTENSION_CHANNEL_TYPE, CHANNEL_TYPE_QUEUE)` |
| `.exchange(exchange, routingKey)` | `.channel(exchange, routingKey)` (`channelExtension`, amqp and memory only) |
| `.topic()` / `.direct()` / `.fanout()` / `.header()` (routing type) | none. Topics go to the topic exchange configured by `exchange` (default `amq.topic`). An exchange named by a channel extension is of type `topic`; it is declared only with `EXTENSION_AUTO_CREATE = true`, otherwise it must exist |
| `.durable()` | `.extensionEntry(EXTENSION_DURABLE, true)` |
| `.exclusive()` / `.autoDelete()` | none. Topic subscriptions get exclusive server-named queues |
| `.expiration("60000")` | `.extensionEntry(EXTENSION_TIME_TO_LIVE, 60_000L)` |
| `.autoAcknowledge()`, `amqp.autoAcknowledge` | QoS 0 subscription (`EXTENSION_QOS = QOS_AT_MOST_ONCE`). Without an acknowledge mode, messages are acknowledged on receipt |
| `.priority`, `.appId`, `.clusterId`, `.userId`, `.timestamp`, `.messageId` | none. They are not mapped. The amqp binding maps only content type/encoding, correlation id, reply-to, expiration (TTL), the delivery mode (from `EXTENSION_DURABLE`) and the headers table. Received header values of other AMQP field types become `String`s |
| `.deliveryMode(2)` | `.extensionEntry(EXTENSION_DURABLE, true)` |
| `.asRPCRequest(..)` / `.asRPCResponse(..)` | `.replyTo(..)` / `.correlationId(..)`. Generated if `generateReplyChannel` / `generateCorrelationId` are announced |
| `MQTTContextBuilder.withQoS(QoS.AT_LEAST_ONE)` | `.extensionEntry(EXTENSION_QOS, QOS_AT_LEAST_ONCE)` (`QOS_AT_MOST_ONCE` = 0, `QOS_EXACTLY_ONCE` = 2) |
| `MQTTContextBuilder.retained()` | `.extensionEntry(EXTENSION_RETAIN, true)` |
| `EventAdminMessagingContextBuilder.header(s)` | `MessageContextBuilder.header(key, value)` / `headers(map)` |
| `acknowledgeFilter(Predicate)` | `adapt(AcknowledgeMessageContextBuilder.class).acknowledgeFilter(..)` |
| `acknowledgeFunction(Consumer)` | `AcknowledgeMessageContextBuilder.acknowledgeFunction(..)` (decides with the `AcknowledgeHandler`) or `postAcknowledge(..)` |
| `negativeAcknowledgeFunction(Consumer)` | `AcknowledgeMessageContextBuilder.postReject(..)` |
| `acknowledgeErrorFunction(BiConsumer)` | none |
| `AMQPMessage.getDeliveryTag()` + channel ack | `((AcknowledgeMessageContext) m.getContext()).getAcknowledgeHandler().acknowledge()` / `reject()` |
| `withBuffer`, `withBufferQueue`, `withPushbackPolicy`, `withParallelism`, `withExecutor`, `withScheduler`, `withPushstreamContext`, `pushstream.*` properties | per instance: `bufferSize` (default 1024), `queuePolicy` (`BLOCK`, `DISCARD_OLDEST`, `FAIL`), `workerThreads` (default 4). For anything more, use operations on the returned `PushStream` |
| `amqp.basicQos` | `prefetch` (default 100) |
| `amqp.maxActivateRPCWorker` | nearest: `workerThreads`, but not an exact equivalent. `workerThreads` (default 4) are the delivery threads of the whole instance; the reply-to whiteboard runs on them and falls back to the receiving thread beyond `bufferSize` requests in flight |
| `amqp.singleActiveConsumer`, `amqp.exclusiveQueue` | none |
| capability `osgi.message.adapter` / `gecko.messaging`, `@RequireMQTTv3`, `@RequireMQTTv5`, `@RequireRPCv3`, `@RequireRPCv5`, `@RequireEventAdminMessageAdapter`, `@RequireMessageAdapter`, `@RequireRPCRequestService`, `@RequireRPCWorkerService` | `@RequireMessaging(protocol = "mqtt3" \| "mqtt" \| "amqp" \| "websocket" \| "memory")` → `osgi.implementation=osgi.messaging` |
| none | `ConnectionListener` (whiteboard), `MessageServiceRuntime.getRuntimeDTO()` / `getConnectionState()` |

**PIDs.** In 2.0 each binding uses one factory PID, which is its DS component name.

| Old PID(s) | New factory PID |
|---|---|
| `AMQPService`, `AMQPRPCService`, `AMQPReplyToService`, `AMQPRPCRequestService`, `AMQPubOnSubService`, `AMQPReplyToExecutor`, `AMQPRPCWorkerService` | `org.gecko.messaging.amqp` (one configuration for all roles) |
| `MQTTService`, `MQTTRPCService` (Paho v3) | `org.gecko.messaging.mqtt3` |
| `MQTTServiceV5`, `MQTTRPCServiceV5` (Paho v5) | `org.gecko.messaging.mqtt` |
| `EventAdminTopicSubscription` | none (see section 5) |
| `MQTTBroker` (Moquette) | none (see section 5) |
| none | `org.gecko.messaging.memory`, `org.gecko.messaging.websocket`, `org.gecko.messaging.websocket.hub` |

**Configuration keys.**

| Old key | New key |
|---|---|
| none (the PID identified the service) | `osgi.messaging.name`, **required**. Used as the target filter |
| `brokerUrl` | `uri` (`tcp://`, `ssl://`, `ws://`, `wss://` for MQTT, `amqp://` or `amqps://` for AMQP, `ws://` or `wss://` for WebSocket, `memory://<broker>`) |
| `host`, `port`, `virtualHost` (AMQP) | part of `uri`: `amqp://host:port/vhost` |
| `username` | `username` |
| `password` (AMQP), `password` / `.password` / `_password` (MQTT) | `.password` (`password` is still read as a fallback) |
| `autoRecovery` | none. Use `reconnectDelay` (default 1000 ms) and `reconnectMaxDelay` (default 30000 ms) |
| `jmx` | none. Use `MessageServiceRuntime` |
| `tls`, `keyStore`, `keyStorePassword`, `trustStore`, `trustStorePassword` | a TLS scheme in `uri`. There are no keystore keys: set the JVM defaults (`javax.net.ssl.keyStore`, `javax.net.ssl.trustStore`, …) |
| `exchange`, `routingKey`, `topic` (AMQP, default channel) | `context.channel`, `context.channelExtension`. The `exchange` key now means the topic exchange (default `amq.topic`) |
| `rpcQueue`, `rpcRoutingKey` (`AMQPReplyToExecutor`) | handler properties `osgi.messaging.replyToSubscription.channel` / `.channelExtension` |
| `name` (AMQP, used for thread names) | `osgi.messaging.name` |
| `immediateChannel` | none |
| `inflightPersistence`, `filePersistencePath` (MQTT) | none. Paho `MemoryPersistence` only; in-flight state is in memory |
| `maxThreads` (MQTT) | `workerThreads`. On WebSocket, `maxThreads` sizes the Jetty client |
| `maxInflight` (MQTT) | `maxInflight` (mqtt3, default 100) / `receiveMaximum` (mqtt, default 100) |
| none (old AMQP responders answered any `replyTo`) | `replyToAllowed`: prefixes of reply channels that are answered; default `osgi/messaging/reply/` and the own `replyChannelPrefix` subtree; `*` for any |
| none | `clientId`, `connectTimeout`, `replyTimeout`, `replyChannelPrefix`, `replyToAllowed`, `cloudEvents`, `qos`, `keepAliveInterval`, `cleanSession` / `cleanStart`, `sessionExpiryInterval`, `sharedGroup`, `maxMessageSize`, `heartbeat`, `prefetch`, `maxPublishSessions` |

Example (Configurator):

```json
"org.gecko.messaging.amqp~backend": {
  "osgi.messaging.name": "backend",
  "uri": "amqps://rabbit.example:5671/prod",
  "username": "svc-orders",
  ".password": "…",
  "prefetch": 50
}
```

### 4.3 Publishing

Before:

```java
@Reference
MessagingService messaging;

MessagingContext ctx = new MQTTContextBuilder().withQoS(QoS.AT_LEAST_ONE).retained().build();
messaging.publish("sensors/temp", ByteBuffer.wrap(bytes), ctx);   // throws Exception
```

After:

```java
import static org.gecko.messaging.api.MessagingConstants.*;

@Reference(target = "(osgi.messaging.name=sensors)")
MessagePublisher publisher;

publisher.publish(b -> b.channel("sensors/temp")
        .content(ByteBuffer.wrap(bytes))
        .extensionEntry(EXTENSION_QOS, QOS_AT_LEAST_ONCE)
        .extensionEntry(EXTENSION_RETAIN, true)
        .buildMessage())
    .onFailure(t -> log.warn("publish failed", t));                // e.g. FeatureNotSupportedException
```

### 4.4 Subscribing

Before:

```java
PushStream<Message> stream = messaging.subscribe("sensors/#");
stream.forEach(m -> handle(m.topic(), m.payload()));
```

After:

```java
@Reference(target = "(osgi.messaging.name=sensors)")
MessageSubscription subscription;

@Reference(target = "(osgi.messaging.name=sensors)", scope = ReferenceScope.PROTOTYPE_REQUIRED)
MessageContextBuilder builder;

MessageContext ctx = builder.channel("sensors/#").extensionEntry(EXTENSION_QOS, QOS_AT_LEAST_ONCE).buildContext();
PushStream<Message> stream = subscription.subscribe(ctx);          // or subscribe("sensors/#")
stream.forEach(m -> handle(m.getContext().getChannel(), m.payload()));
// stream.close() ends the subscription; it survives reconnects until then
```

To follow connection gaps, register a `ConnectionListener` with
`@MessagingTarget("(osgi.messaging.name=sensors)")`. `ConnectionEvent.reconnect()` and
`sessionPresent()` tell you whether you need to resynchronise.

### 4.5 RPC request (caller)

Before (AMQP):

```java
@Reference
MessagingReplyToService replyTo;

Promise<Message> p = replyTo.publishSingle("calc", ByteBuffer.wrap(request));   // no timeout
PushStream<Message> many = replyTo.publishMany("report", ByteBuffer.wrap(request));
```

After:

```java
@Reference(target = "(osgi.messaging.name=backend)")
ReplyToPublisher replyTo;

Message req = builder.channel("calc")
        .extensionEntry(EXTENSION_CHANNEL_TYPE, CHANNEL_TYPE_QUEUE)
        .extensionEntry(EXTENSION_REPLY_TIMEOUT, 5_000L)
        .content(ByteBuffer.wrap(request))
        .buildMessage();
Promise<Message> p = replyTo.publishWithReply(req);               // fails with TimeoutException
PushStream<Message> many = replyTo.publishWithReplyMany(req);      // ends at header "replycomplete"
```

The reply channel and correlation id are generated when the instance announces
`generateReplyChannel` and `generateCorrelationId`. Generated reply channels start with
`osgi/messaging/reply/`, which responders accept by default.

### 4.6 RPC handler (responder)

Before (`AMQPubOnSubService` with a `WorkerFunction` service, configured by its own PID with
host, port, exchange and routing key):

```java
@Component(service = WorkerFunction.class)
public class CalcWorker implements WorkerFunction {
    @Override
    public ByteBuffer apply(AMQPMessage request) {
        return compute(request.payload());
    }
}
```

After:

```java
import org.gecko.messaging.api.propertytypes.MessagingTarget;
import org.gecko.messaging.api.propertytypes.ReplyToSubscription;

@Component
@MessagingTarget("(osgi.messaging.name=backend)")
@ReplyToSubscription(channel = "calc", channelType = "queue")      // "queue": competing handlers
public class CalcHandler implements ReplyToSingleSubscriptionHandler {
    @Override
    public Message handleResponse(Message request, MessageContextBuilder response) {
        // response is already set to the request's reply-to channel and correlation id
        return response.content(compute(request.payload())).buildMessage();
    }
}
```

For streamed answers (formerly `AMQPReplyToExecutor` with `ma.posFunction`), implement
`ReplyToManySubscriptionHandler.handleResponses(request, responseBuilders)` and return a
`PushStream<Message>`. The whiteboard sends the closing `replycomplete` message itself. A handler
without `osgi.messaging.target` is not bound.

To fail a request, return a response with the header `replyerror` (`HEADER_REPLY_ERROR`) and a
short reason. The caller's promise fails, or its stream of responses ends with an error. Do not
put exception text in the reason.

**Migration impact of `replyToAllowed`.** The old AMQP responders answered any `replyTo` a request
named. In 2.0 a request is answered only when its reply channel starts with
`osgi/messaging/reply/` or with the responder instance's own `replyChannelPrefix`. Other requests
are refused and counted in `ReplyToSubscriptionDTO.refusedRequests`. If your callers are still
1.x clients, or use fixed reply queues such as `calc.replies`, either let the 2.0 callers generate
reply channels, or list the prefixes in `replyToAllowed` on the responder instance. A prefix is
matched as given, so end it with the separator to match a subtree. `*` allows any channel and
restores the old, unsafe behaviour.

### 4.7 Manual acknowledge

Before (AMQP only):

```java
AMQPContextBuilder b = new AMQPContextBuilder().topic().durable().queue("orders");
b.acknowledgeFilter(m -> isValid(m));                              // false → basicReject, never re-queued
PushStream<Message> s = amqp.subscribe("orders", b.build());
```

After (any binding that announces `acknowledge`):

```java
MessageContext ctx = builder.channel("orders")
        .extensionEntry(EXTENSION_CHANNEL_TYPE, CHANNEL_TYPE_QUEUE)
        .adapt(AcknowledgeMessageContextBuilder.class)
        .manualAcknowledge()
        .requeueOnReject(true)                                     // queues only, at most once
        .messageContextBuilder()
        .buildContext();
subscription.subscribe(ctx).forEach(m -> {
    AcknowledgeHandler h = ((AcknowledgeMessageContext) m.getContext()).getAcknowledgeHandler();
    if (process(m)) {
        h.acknowledge();
    } else {
        h.reject();
    }
});
```

`acknowledgeFilter(..)` and `acknowledgeFunction(..)` are still available. They decide before a
message enters the stream. Messages that are still open when a manual stream closes are rejected.

## 5. Not carried over

| 1.x part | Status in 2.0 | Replacement |
|---|---|---|
| `org.gecko.adapter.eventadmin` (`EventAdminMessageService`, `EventAdminTopicSubscription`, PushStream-as-service) | dropped | For in-process messaging, use the `memory` binding (`org.gecko.messaging.memory`, `uri=memory://<broker>`). It supports all features except CloudEvents by default. Code that only needs OSGi events can use `EventAdmin` directly |
| `org.gecko.moquette.broker` (`MQTTBroker`, PID `MQTTBroker`) | dropped | No embedded broker. Use an external broker such as Mosquitto, HiveMQ, EMQX or RabbitMQ. For tests, use the `memory` binding. For WebSocket, there is the `org.gecko.messaging.websocket.hub` relay (localhost by default, `allowedOrigins` empty) |
| JMX (`AMQPServiceMetricMBean`, `AMQPConsumerMetricMBean`, `Messaging:name=…`) | dropped | `MessageServiceRuntime.getRuntimeDTO()`: `MessagingRuntimeDTO` with connection state, `reconnects`, `SubscriptionDTO.receivedMessages` and `ReplyToSubscriptionDTO`. There is no last-message time and no message rate |
| Re-subscription SPI (`BasicReSubscriber`, `BasicReSubscribeConsumer(Factory)`, `ReSubscribeCallback`) | dropped | Restoring subscriptions is built in |
| AMQP routing types `direct`, `fanout`, `headers`, exclusive or auto-delete flags, `singleActiveConsumer` | dropped | Topic exchange plus named queues. A channel extension addresses another exchange of type `topic`, declared only with `autoCreate=true`; otherwise it must exist |
| MQTT file persistence (`inflightPersistence=FILE`) | dropped | Paho `MemoryPersistence` only. Keep the session on the broker instead (`cleanSession=false` / `sessionExpiryInterval`) |
| `org.gecko.util.pushstream` helpers (`PushStreamHelper`, `pushstream.*` keys) | dropped | `bufferSize`, `queuePolicy`, `workerThreads`, plus standard `PushStream` operations |
| Paho v3 / v5 RPC components (`MQTTRPCService`, `MQTTRPCServiceV5`) | dropped | `ReplyToPublisher` of the `mqtt` binding (MQTT 5 response topic and correlation data). On `mqtt3`, only with `cloudEvents=structured` |
