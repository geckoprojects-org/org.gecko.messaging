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
import org.gecko.messaging.api.FeatureNotSupportedException;
import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.MessagingException;
import org.gecko.messaging.core.AbstractMessagingInstance;
import org.gecko.messaging.core.Acknowledger;
import org.gecko.messaging.core.Extensions;
import org.gecko.messaging.core.MessagingConfiguration;
import org.gecko.messaging.core.SimpleMessageContext;
import org.gecko.messaging.core.SimpleMessageContextBuilder;
import org.gecko.messaging.core.SubscriptionGroup;
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
 * {@code sharedGroup} (group of point-to-point subscriptions, default
 * {@value #DEFAULT_SHARED_GROUP}: every instance on a queue competes for its messages, whatever its
 * name).
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
	/**
	 * Shared subscription group of a queue. One group for all instances: a queue is one set of
	 * competing receivers, and MQTT keeps the groups of different topic filters apart.
	 */
	static final String DEFAULT_SHARED_GROUP = "messaging";

	private final String sharedGroup;
	/** Largest packet received and message sent, in bytes. */
	private final long maxMessageSize;
	private volatile MqttAsyncClient client;
	/** Broker subscriptions by topic filter, with the local subscriptions they feed. */
	private final Map<String, SubscriptionGroup> filters = new ConcurrentHashMap<>();

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
		this.sharedGroup = config.string("sharedGroup", DEFAULT_SHARED_GROUP);
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
	protected boolean createsChannelsImplicitly() {
		return true;
	}

	/** User property names the binding uses itself; an application header must not take them. */
	static final Set<String> RESERVED_PROPERTIES = Set.of(REQUEUED_PROPERTY, CONTENT_ENCODING_PROPERTY);

	@Override
	protected void checkContext(MessageContext context, boolean publish) {
		for (String key : context.getHeaders().keySet()) {
			if (RESERVED_PROPERTIES.contains(key)) {
				// it would be stripped (requeued) or read as content encoding on receipt
				throw new FeatureNotSupportedException(FEATURE_HEADERS,
						"The header '" + key + "' is reserved by the MQTT binding");
			}
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
		int q = Extensions.qos(context);
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
		message.setRetained(Boolean.TRUE.equals(Extensions.bool(context.getExtensions().get(EXTENSION_RETAIN))));
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
		Long ttl = Extensions.number(context.getExtensions().get(EXTENSION_TIME_TO_LIVE));
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
		SubscriptionGroup group = filters.computeIfAbsent(filter, f -> new SubscriptionGroup(f.startsWith("$share/")));
		int qos = group.add(handle, qos(handle.context()));
		if (qos < 0) {
			return; // the broker subscription serves it
		}
		MqttAsyncClient c = client();
		IMqttMessageListener listener = (topic, message) -> group.dispatch(context(topic, message), message.getPayload(),
				new SubscriptionGroup.Broker() {
					@Override
					public void complete() {
						MqttInstance.this.complete(c, message);
					}

					@Override
					public void requeue() {
						MqttInstance.this.requeue(c, topic, message);
					}
				});
		try {
			MqttSubscription subscription = new MqttSubscription(filter, qos);
			if (group.isSubscribed()) {
				// subscribed again for a higher QoS: retained messages only for a new subscription,
				// the existing receivers have them
				subscription.setRetainHandling(1);
			}
			IMqttToken token = c.subscribe(new MqttSubscription[] { subscription }, null, null, listener, new MqttProperties());
			token.waitForCompletion(config.connectTimeout());
			int[] codes = token.getReasonCodes();
			if (codes != null && codes.length > 0 && codes[0] >= 0x80) {
				throw new MessagingException("The broker refused the subscription of " + filter + " (reason code 0x"
						+ Integer.toHexString(codes[0]) + ")");
			}
			group.subscribed(codes != null && codes.length > 0 ? codes[0] : qos);
		} catch (Exception e) {
			if (group.remove(handle)) {
				filters.remove(filter, group);
			}
			throw e;
		}
	}

	@Override
	protected void doUnsubscribe(SubscriptionHandle handle) {
		String filter = topicFilter(handle);
		SubscriptionGroup group = filters.get(filter);
		if (group == null || !group.remove(handle)) {
			return;
		}
		filters.remove(filter, group);
		MqttAsyncClient c = client;
		if (c != null && c.isConnected()) {
			try {
				c.unsubscribe(filter);
			} catch (MqttException | RuntimeException e) {
				LOG.log(Level.FINE, "MQTT unsubscribe of " + filter + " failed", e);
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
		SimpleMessageContextBuilder b = new SimpleMessageContextBuilder().channel(topic);
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
		return b.buildSimple();
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
