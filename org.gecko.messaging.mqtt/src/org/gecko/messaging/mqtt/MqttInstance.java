/**
 * Copyright (c) 2012 - 2026 Data In Motion and others.
 * All rights reserved.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
package org.gecko.messaging.mqtt;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.gecko.messaging.api.MessagingConstants.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttActionListener;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.MessageContextBuilder;
import org.gecko.messaging.api.MessagingException;
import org.gecko.messaging.core.AbstractMessagingInstance;
import org.gecko.messaging.core.Acknowledger;
import org.gecko.messaging.core.Features;
import org.gecko.messaging.core.MessagingConfiguration;
import org.gecko.messaging.core.SimpleMessageContext;
import org.gecko.messaging.core.SimpleMessageContextBuilder;
import org.gecko.messaging.core.SubscriptionHandle;
import org.osgi.util.promise.Deferred;
import org.osgi.util.promise.Promise;

/**
 * A messaging instance over MQTT 5 with the Paho client — one client per instance.
 * <p>
 * The instance owns reconnecting: Paho's automatic reconnect is off, every connect attempt has a
 * deadline, subscriptions are restored by the instance. Received QoS 1/2 messages are
 * acknowledged manually — on receipt, or when the subscription decides — so the broker's
 * {@code receiveMaximum} bounds the unacknowledged messages. Point-to-point channels are shared
 * subscriptions ({@code $share/<group>/<topic>}); within the instance a queue message goes to one
 * of its local subscriptions, in turn. Reply-to uses the MQTT 5 response topic and correlation
 * data. A rejected message is acknowledged and dropped; with re-queue it is published again
 * unchanged and the original acknowledged (MQTT redelivers unacknowledged messages only on a
 * reconnect with a kept session).
 * <p>
 * Configuration, besides the common keys: {@code uri} ({@code tcp://}, {@code ssl://},
 * {@code ws://}, {@code wss://}), {@code cleanStart} (default {@code true}),
 * {@code sessionExpiryInterval} (s, default 0), {@code keepAliveInterval} (s, default 30),
 * {@code receiveMaximum} (default 100), {@code qos} (default publish and subscribe QoS, default 1),
 * {@code maxMessageSize} (bytes, default 1 MiB, packets received and messages sent),
 * {@code sharedGroup} (group of point-to-point subscriptions, default the instance name).
 */
public final class MqttInstance extends AbstractMessagingInstance {

	private static final Logger LOG = Logger.getLogger(MqttInstance.class.getName());

	/** The protocol name. */
	public static final String PROTOCOL = "mqtt";

	/**
	 * User property marking a message published again after a reject with re-queue; such a
	 * message is not re-queued a second time ({@link Acknowledger#MAX_REQUEUES}). Not a header.
	 */
	static final String REQUEUED_PROPERTY = "requeued";

	/** User property carrying the content encoding, which MQTT 5 has no field for. */
	public static final String CONTENT_ENCODING_PROPERTY = "content-encoding";

	static final List<String> FEATURES = List.of(FEATURE_PUBLISH_SUBSCRIBE, FEATURE_POINT_TO_POINT, FEATURE_WILDCARD,
			FEATURE_CONTENT_TYPE, FEATURE_HEADERS, FEATURE_QOS, FEATURE_TIME_TO_LIVE, FEATURE_RETAIN, FEATURE_AUTO_CREATE,
			FEATURE_ACKNOWLEDGE, FEATURE_REPLY_TO, FEATURE_REPLY_TO_MANY, FEATURE_REPLY_TO_SUBSCRIPTION,
			FEATURE_GENERATE_CORRELATION_ID, FEATURE_GENERATE_REPLY_CHANNEL);

	/** MQTT 5 reason codes that end the reconnect cycle: bad credentials, not authorised, bad auth method, banned. */
	private static final Set<Integer> FATAL = Set.of(0x86, 0x87, 0x8A, 0x8C);

	private final int defaultQos;
	private final String sharedGroup;
	/** Largest packet received and message sent, in bytes. */
	private final long maxMessageSize;
	private volatile MqttAsyncClient client;
	/** Broker subscriptions by topic filter, with the local subscriptions they feed. */
	private final Map<String, Set<SubscriptionHandle>> filters = new ConcurrentHashMap<>();
	/** Turns of the local queue subscriptions, see {@link #dispatch}. */
	private final AtomicInteger queueTurn = new AtomicInteger();

	/**
	 * Creates the instance.
	 *
	 * @param config the configuration
	 */
	public MqttInstance(MessagingConfiguration config) {
		super(PROTOCOL, config, FEATURES);
		if (config.uri() == null) {
			throw new MessagingException("An MQTT instance needs a 'uri'");
		}
		this.defaultQos = (int) config.number("qos", QOS_AT_LEAST_ONCE);
		this.sharedGroup = config.string("sharedGroup", config.name());
		this.maxMessageSize = Math.min(Integer.MAX_VALUE, config.number("maxMessageSize", 1 << 20));
	}

	@Override
	protected boolean doConnect(long timeoutMillis) throws Exception {
		MqttAsyncClient c = new MqttAsyncClient(config.uri(), config.clientId(), new MemoryPersistence());
		c.setCallback(new Callback(c));
		c.setManualAcks(true);
		MqttConnectionOptions options = new MqttConnectionOptions();
		options.setAutomaticReconnect(false);
		options.setCleanStart(config.bool("cleanStart", true));
		options.setSessionExpiryInterval(config.number("sessionExpiryInterval", 0));
		options.setKeepAliveInterval((int) config.number("keepAliveInterval", 30));
		options.setConnectionTimeout((int) Math.max(1, (timeoutMillis + 999) / 1000));
		options.setReceiveMaximum((int) config.number("receiveMaximum", 100));
		options.setMaximumPacketSize(maxMessageSize);
		if (config.username() != null) {
			options.setUserName(config.username());
		}
		if (config.password() != null) {
			options.setPassword(config.password().getBytes(UTF_8));
		}
		client = c;
		filters.clear();
		IMqttToken token = c.connect(options);
		token.waitForCompletion(timeoutMillis);
		if (!c.isConnected()) {
			throw new MessagingException("MQTT connect to " + config.displayUri() + " did not complete");
		}
		return token.getSessionPresent();
	}

	@Override
	protected void doDisconnect() {
		MqttAsyncClient c = client;
		client = null;
		filters.clear();
		if (c == null) {
			return;
		}
		try {
			if (c.isConnected()) {
				c.disconnectForcibly(0, 1000, true);
			}
		} catch (MqttException | RuntimeException e) {
			LOG.log(Level.FINE, "MQTT disconnect failed", e);
		}
		try {
			c.close(true);
		} catch (MqttException | RuntimeException e) {
			LOG.log(Level.FINE, "MQTT close failed", e);
		}
	}

	@Override
	protected long maxMessageSize() {
		return maxMessageSize;
	}

	@Override
	protected boolean isFatal(Throwable failure) {
		return failure instanceof MqttException e && FATAL.contains(e.getReasonCode());
	}

	@Override
	protected String cloudEventHeaderPrefix() {
		// CloudEvents MQTT binding, binary mode: attributes are user properties without prefix
		return "";
	}

	private MqttAsyncClient client() {
		MqttAsyncClient c = client;
		if (c == null || !c.isConnected()) {
			throw new MessagingException("MQTT client of " + getName() + " is not connected");
		}
		return c;
	}

	private int qos(MessageContext context) {
		int q = Features.qos(context);
		return q < 0 ? defaultQos : q;
	}

	private String topicFilter(SubscriptionHandle h) {
		if (CHANNEL_TYPE_QUEUE.equals(h.context().getExtensions().get(EXTENSION_CHANNEL_TYPE))) {
			return "$share/" + sharedGroup + "/" + h.channel();
		}
		return h.channel();
	}

	// ------------------------------------------------------------------ publish

	@Override
	protected Promise<Void> doPublish(SimpleMessageContext context, ByteBuffer payload) throws Exception {
		byte[] bytes = new byte[payload.remaining()];
		payload.get(bytes);
		MqttMessage message = new MqttMessage(bytes);
		message.setQos(qos(context));
		message.setRetained(Boolean.TRUE.equals(Features.bool(context.getExtensions().get(EXTENSION_RETAIN))));
		MqttProperties props = new MqttProperties();
		if (context.getContentType() != null) {
			props.setContentType(context.getContentType());
		}
		if (context.getReplyToChannel() != null) {
			props.setResponseTopic(context.getReplyToChannel());
		}
		if (context.getCorrelationId() != null) {
			props.setCorrelationData(context.getCorrelationId().getBytes(UTF_8));
		}
		Long ttl = Features.number(context.getExtensions().get(EXTENSION_TIME_TO_LIVE));
		if (ttl != null) {
			props.setMessageExpiryInterval(Math.max(1, (ttl + 999) / 1000));
		}
		List<UserProperty> user = new ArrayList<>();
		if (context.getContentEncoding() != null) {
			user.add(new UserProperty(CONTENT_ENCODING_PROPERTY, context.getContentEncoding()));
		}
		context.getHeaders().forEach((k, v) -> user.add(new UserProperty(k, String.valueOf(v))));
		if (!user.isEmpty()) {
			props.setUserProperties(user);
		}
		message.setProperties(props);
		Deferred<Void> deferred = promises.deferred();
		client().publish(context.getChannel(), message, null, new MqttActionListener() {
			@Override
			public void onSuccess(IMqttToken token) {
				deferred.resolve(null);
			}

			@Override
			public void onFailure(IMqttToken token, Throwable failure) {
				deferred.fail(failure == null ? new MessagingException("MQTT publish failed") : failure);
			}
		});
		return deferred.getPromise();
	}

	// ------------------------------------------------------------------ subscribe

	@Override
	protected void doSubscribe(SubscriptionHandle handle) throws Exception {
		String filter = topicFilter(handle);
		int qos = qos(handle.context());
		Set<SubscriptionHandle> handles = filters.computeIfAbsent(filter, f -> new CopyOnWriteArraySet<>());
		boolean first;
		synchronized (handles) {
			first = handles.isEmpty();
			handles.add(handle);
		}
		if (!first) {
			return;
		}
		MqttAsyncClient c = client();
		boolean queue = filter.startsWith("$share/");
		IMqttMessageListener listener = (topic, message) -> dispatch(c, handles, queue, topic, message);
		try {
			IMqttToken token = c.subscribe(new MqttSubscription[] { new MqttSubscription(filter, qos) }, null, null, listener,
					new MqttProperties());
			token.waitForCompletion(config.connectTimeout());
			int[] codes = token.getReasonCodes();
			if (codes != null && codes.length > 0 && codes[0] >= 0x80) {
				throw new MessagingException("The broker refused the subscription of " + filter + " (reason code 0x"
						+ Integer.toHexString(codes[0]) + ")");
			}
		} catch (Exception e) {
			synchronized (handles) {
				handles.remove(handle);
				if (handles.isEmpty()) {
					filters.remove(filter, handles);
				}
			}
			throw e;
		}
	}

	@Override
	protected void doUnsubscribe(SubscriptionHandle handle) {
		String filter = topicFilter(handle);
		Set<SubscriptionHandle> handles = filters.get(filter);
		if (handles == null) {
			return;
		}
		boolean last;
		synchronized (handles) {
			handles.remove(handle);
			last = handles.isEmpty();
			if (last) {
				filters.remove(filter, handles);
			}
		}
		MqttAsyncClient c = client;
		if (last && c != null && c.isConnected()) {
			try {
				c.unsubscribe(filter);
			} catch (MqttException | RuntimeException e) {
				LOG.log(Level.FINE, "MQTT unsubscribe of " + filter + " failed", e);
			}
		}
	}

	private void dispatch(MqttAsyncClient c, Set<SubscriptionHandle> handles, boolean queue, String topic,
			MqttMessage message) {
		List<SubscriptionHandle> targets = new ArrayList<>(handles);
		if (queue && targets.size() > 1) {
			// competing consumers: one local subscription gets the message
			targets = List.of(targets.get(Math.floorMod(queueTurn.getAndIncrement(), targets.size())));
		}
		AtomicInteger open = new AtomicInteger(Math.max(1, targets.size()));
		Runnable done = () -> {
			if (open.decrementAndGet() == 0) {
				complete(c, message);
			}
		};
		if (targets.isEmpty()) {
			done.run();
			return;
		}
		SimpleMessageContext ctx = context(topic, message);
		byte[] payload = message.getPayload();
		for (int i = 0; i < targets.size(); i++) {
			SubscriptionHandle h = targets.get(i);
			byte[] own = i == targets.size() - 1 ? payload : payload.clone();
			if (h.needsAcknowledger()) {
				h.deliver(ctx, own, new Acknowledger() {
					@Override
					public void acknowledge() {
						done.run();
					}

					@Override
					public void reject(boolean requeue) {
						if (requeue) {
							requeue(c, topic, message);
						}
						done.run();
					}
				});
			} else {
				h.deliver(ctx, own, null);
				done.run();
			}
		}
	}

	/**
	 * Publishes a rejected message again, marked as re-queued; the broker picks the receiver anew.
	 * A message re-queued before is dropped.
	 */
	private void requeue(MqttAsyncClient c, String topic, MqttMessage message) {
		try {
			MqttProperties props = message.getProperties();
			List<UserProperty> user = props.getUserProperties() == null ? new ArrayList<>()
					: new ArrayList<>(props.getUserProperties());
			if (user.stream().anyMatch(u -> REQUEUED_PROPERTY.equals(u.getKey()))) {
				LOG.fine(() -> "Dropping a rejected message on " + topic + ": it was re-queued before");
				return;
			}
			user.add(new UserProperty(REQUEUED_PROPERTY, "1"));
			props.setUserProperties(user);
			// subscription identifiers belong to the received PUBLISH; a client must not send them
			props.setSubscriptionIdentifiers(new ArrayList<>());
			c.publish(topic, new MqttMessage(message.getPayload(), message.getQos(), false, props));
		} catch (MqttException | RuntimeException e) {
			LOG.log(Level.WARNING, "MQTT re-queue of a rejected message on " + topic + " failed", e);
		}
	}

	private void complete(MqttAsyncClient c, MqttMessage message) {
		if (message.getQos() == 0) {
			return;
		}
		try {
			c.messageArrivedComplete(message.getId(), message.getQos());
		} catch (MqttException | RuntimeException e) {
			LOG.log(Level.FINE, "MQTT acknowledgement failed", e);
		}
	}

	private static SimpleMessageContext context(String topic, MqttMessage message) {
		MessageContextBuilder b = new SimpleMessageContextBuilder().channel(topic);
		MqttProperties p = message.getProperties();
		if (p != null) {
			b.contentType(p.getContentType());
			b.replyTo(p.getResponseTopic());
			if (p.getCorrelationData() != null) {
				b.correlationId(new String(p.getCorrelationData(), UTF_8));
			}
			if (p.getUserProperties() != null) {
				for (UserProperty u : p.getUserProperties()) {
					if (REQUEUED_PROPERTY.equals(u.getKey())) {
						continue;
					}
					if (CONTENT_ENCODING_PROPERTY.equals(u.getKey())) {
						b.contentEncoding(u.getValue());
					} else {
						b.header(u.getKey(), u.getValue());
					}
				}
			}
		}
		b.extensionEntry(EXTENSION_QOS, message.getQos());
		if (message.isRetained()) {
			b.extensionEntry(EXTENSION_RETAIN, Boolean.TRUE);
		}
		return (SimpleMessageContext) b.buildContext();
	}

	private final class Callback implements MqttCallback {

		private final MqttAsyncClient owner;

		Callback(MqttAsyncClient owner) {
			this.owner = owner;
		}

		@Override
		public void disconnected(MqttDisconnectResponse response) {
			if (client == owner) {
				connectionLost(response == null ? null : response.getException());
			}
		}

		@Override
		public void mqttErrorOccurred(MqttException exception) {
			LOG.log(Level.WARNING, "MQTT error on " + getName(), exception);
		}

		@Override
		public void messageArrived(String topic, MqttMessage message) {
			// only messages no subscription listener took, left over from an ended subscription;
			// acknowledge and drop
			complete(owner, message);
		}

		@Override
		public void deliveryComplete(IMqttToken token) {
			// publish promises complete through their own listeners
		}

		@Override
		public void connectComplete(boolean reconnect, String serverURI) {
			// the instance tracks the connection itself
		}

		@Override
		public void authPacketArrived(int reasonCode, MqttProperties properties) {
			// no enhanced authentication
		}

	}

}
