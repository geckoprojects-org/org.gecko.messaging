[![CI Build](https://github.com/geckoprojects-org/org.gecko.messaging/actions/workflows/build.yml/badge.svg)](https://github.com/geckoprojects-org/org.gecko.messaging/actions/workflows/build.yml)

# Gecko Messaging 2.0

A protocol-neutral messaging API for OSGi, written spec-first, with implementations for MQTT 5,
MQTT 3.1.1, AMQP 0-9-1, WebSocket and an in-memory broker, and a technology compatibility kit
(TCK) every implementation passes.

- **One API, many protocols.** Publish, subscribe, point-to-point queues, reply-to (request /
  response, also streamed) and acknowledgement through `org.gecko.messaging.api`, whatever the
  broker.
- **Features are announced, not assumed.** Every instance lists what it supports in the service
  property `osgi.messaging.feature`; using anything else fails at once with
  `FeatureNotSupportedException` instead of silently doing less.
- **The instance owns the connection.** Reconnect with backoff and jitter, subscriptions restored
  before the instance reports `CONNECTED`, connection state and events visible to the
  application.
- **Secure by default.** Bounded buffers and message sizes, a message is re-queued at most once,
  responses only to generated reply channels unless configured otherwise, no credentials in
  service properties, a WebSocket hub that binds to localhost and checks the `Origin`.
- **Lean.** Written for small devices: no thread or timer per message or subscription, few
  dependencies.
- **CloudEvents** optionally, in the binary or the structured (JSON) mode.

How to configure and use it, case by case, is the [user guide](docs/guide.md); the
specification draft is [spec/messaging.md](spec/messaging.md); moving from Gecko messaging 1.x is
described in [docs/migration.md](docs/migration.md).

## Modules

| Bundle | |
|---|---|
| `org.gecko.messaging.api` | The API: publisher, subscription, message contexts, acknowledgement, reply-to, runtime, CloudEvents, DTOs |
| `org.gecko.messaging.core` | What all implementations share: lifecycle, reconnect, feature checks, reply-to, CloudEvents codec |
| `org.gecko.messaging.memory` | In-memory broker, every feature, for tests and as reference |
| `org.gecko.messaging.mqtt` | MQTT 5 with Eclipse Paho (build of [geckoprojects-org/paho](https://github.com/geckoprojects-org/paho)) |
| `org.gecko.messaging.mqtt3` | MQTT 3.1.1 with Eclipse Paho, for brokers and devices without MQTT 5 |
| `org.gecko.messaging.amqp` | AMQP 0-9-1 with the RabbitMQ Java client |
| `org.gecko.messaging.websocket` | Raw WebSocket with the Jetty 12 client: a channel is a URL path |
| `org.gecko.messaging.websocket.hub` | An embedded Jetty 12 server relaying the messages of a path to its sessions |
| `org.gecko.messaging.tck` | The TCK, plus one run project per implementation (`*.tck`) |
| `org.gecko.messaging.library.workspace` | Bnd workspace library `geckoMessaging` |
| `org.gecko.messaging.library.project` | Bnd project library `enableMessaging` |

## Using it

In the `cnf/build.bnd` of a bnd workspace:

```
-library: geckoMessaging
```

adds a repository with the messaging bundles and everything they need at runtime. In the
`bnd.bnd` of a project:

```
-library: enableMessaging
```

puts the API on the build path. Pick the implementations in the bndrun and configure one
instance per factory configuration, for example with the Configurator:

```json
"org.gecko.messaging.mqtt~sensors": {
  "osgi.messaging.name": "sensors",
  "uri": "ssl://broker.example:8883",
  "username": "device-17",
  ".password": "…"
}
```

```java
@Reference(target = "(osgi.messaging.name=sensors)")
MessagePublisher publisher;

publisher.publish(message, context)        // Promise<Void>
```

## Building

Java 21 and a container runtime (Docker or Podman) for the brokers of the TCK runs.

```
./gradlew build -x testOSGi                        # bundles and unit tests
./gradlew build brokerUp testOSGi brokerDown       # everything, against Mosquitto and RabbitMQ
```

`brokerUp` starts Mosquitto (port 11883) and RabbitMQ (port 15675, user `tck`) with
[docker/brokers.sh](docker/brokers.sh); the memory and WebSocket runs need no external broker.
Both brokers also listen over TLS and demand a client certificate there (ports 11887 and 15677);
the script makes the certificates on every start and needs `openssl` and `keytool` for it.

All tests run in GitHub Actions on every push and are published as check runs. Jenkins builds
without `testOSGi`, waits for the GitHub check of the commit and publishes snapshots and releases.

## Developers

* **Juergen Albert** (jalbert) / [j.albert@data-in-motion.biz](mailto:j.albert@data-in-motion.biz) @ [Data In Motion](https://www.datainmotion.de) - *architect*, *developer*
* **Mark Hoffmann** (mhoffmann) / [m.hoffmann@data-in-motion.biz](mailto:m.hoffmann@data-in-motion.biz) @ [Data In Motion](https://www.datainmotion.de) - *developer*, *architect*

## License

[Eclipse Public License 2.0](LICENSE)
