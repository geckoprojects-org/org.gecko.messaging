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
package org.gecko.messaging.mqtt3;

import static org.gecko.messaging.api.MessagingConstants.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.gecko.messaging.api.MessageContext;
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
 * A messaging instance over MQTT 3.1.1 with the Paho client — one client per instance, for
 * brokers and devices without MQTT 5.
 * <p>
 * Reconnects, acknowledgement, queues and re-queue work as in the MQTT 5 instance: Paho's
 * automatic reconnect is off, received QoS 1/2 messages are acknowledged manually, point-to-point
 * channels are shared subscriptions ({@code $share/<group>/<topic>}, which MQTT 3.1.1 does not
 * define but brokers such as Mosquitto, HiveMQ and EMQX offer), a re-queued message is published
 * again.
 * <p>
 * MQTT 3.1.1 carries only topic, payload, QoS and the retain flag. Without CloudEvents the
 * instance announces no content type, headers, time-to-live or reply-to; with structured
 * CloudEvents ({@code cloudEvents=structured}) the JSON envelope carries content type, headers,
 * correlation and reply-to, and the instance announces them.
 * <p>
 * Configuration, besides the common keys: {@code uri} ({@code tcp://}, {@code ssl://},
 * {@code ws://}, {@code wss://}), {@code cleanSession} (default {@code true}),
 * {@code keepAliveInterval} (s, default 30), {@code maxInflight} (default 100), {@code qos}
 * (default publish and subscribe QoS, default 1), {@code maxMessageSize} (bytes, default 1 MiB,
 * packets received and messages sent), {@code sharedGroup} (group of point-to-point
 * subscriptions, default {@value #DEFAULT_SHARED_GROUP}: every instance on a queue competes for
 * its messages, whatever its name).
 */
public final class Mqtt3Instance extends AbstractMessagingInstance {

	private static final Logger LOG = Logger.getLogger(Mqtt3Instance.class.getName());

	/** The protocol name. */
	public static final String PROTOCOL = "mqtt3";

	static final List<String> FEATURES = List.of(FEATURE_PUBLISH_SUBSCRIBE, FEATURE_POINT_TO_POINT, FEATURE_WILDCARD,
			FEATURE_QOS, FEATURE_RETAIN, FEATURE_AUTO_CREATE, FEATURE_ACKNOWLEDGE);


	/** Connect return codes that end the reconnect cycle: bad credentials, not authorised. */
	private static final Set<Integer> FATAL = Set.of((int) MqttException.REASON_CODE_FAILED_AUTHENTICATION,
			(int) MqttException.REASON_CODE_NOT_AUTHORIZED);

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
	private final Map<String, Set<SubscriptionHandle>> filters = new ConcurrentHashMap<>();
	/** Most fingerprints of re-queued messages kept, see {@link #requeue}. */
	static final int REQUEUE_MEMORY = 1024;

	/**
	 * Fingerprints (topic and payload) of messages this instance published again after a reject:
	 * MQTT 3.1.1 has no properties to mark such a message, so it is recognized by this bounded set
	 * and dropped when rejected again ({@link Acknowledger#MAX_REQUEUES}). An equal message coming
	 * later is at worst dropped instead of re-queued once. Guarded by itself.
	 */
	private final Map<Long, Boolean> requeued = new LinkedHashMap<>(16, 0.75f, false) {
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
			return size() > REQUEUE_MEMORY;
		}
	};

	/** Turns of the local queue subscriptions, see {@link #dispatch}. */
	private final AtomicInteger queueTurn = new AtomicInteger();

	/**
	 * Creates the instance.
	 *
	 * @param config the configuration
	 */
	public Mqtt3Instance(MessagingConfiguration config) {
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
		MqttConnectOptions options = new MqttConnectOptions();
		options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
		options.setAutomaticReconnect(false);
		options.setCleanSession(config.bool("cleanSession", true));
		options.setKeepAliveInterval((int) config.number("keepAliveInterval", 30));
		options.setConnectionTimeout((int) Math.max(1, (timeoutMillis + 999) / 1000));
		options.setMaxInflight((int) config.number("maxInflight", 100));
		options.setMaxInboundPacketSize((int) maxMessageSize);
		if (config.username() != null) {
			options.setUserName(config.username());
		}
		if (config.password() != null) {
			options.setPassword(config.password().toCharArray());
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

	@Override
	protected long maxMessageSize() {
		return maxMessageSize;
	}

	@Override
	protected boolean isFatal(Throwable failure) {
		return failure instanceof MqttException e && FATAL.contains(e.getReasonCode());
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
		Deferred<Void> deferred = promises.deferred();
		client().publish(context.getChannel(), message, null, new IMqttActionListener() {
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
			IMqttToken token = c.subscribe(filter, qos, null, null, listener);
			token.waitForCompletion(config.connectTimeout());
			int[] granted = token.getGrantedQos();
			if (granted != null && granted.length > 0 && granted[0] >= 0x80) {
				throw new MessagingException("The broker refused the subscription of " + filter);
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
	 * Publishes a rejected message again, as it came; the broker picks the receiver anew. A message
	 * re-queued before is dropped.
	 */
	private void requeue(MqttAsyncClient c, String topic, MqttMessage message) {
		long fingerprint = ((long) topic.hashCode() << 32) ^ (Arrays.hashCode(message.getPayload()) & 0xffffffffL);
		synchronized (requeued) {
			if (requeued.remove(fingerprint) != null) {
				LOG.fine(() -> "Dropping a rejected message on " + topic + ": it was re-queued before");
				return;
			}
			requeued.put(fingerprint, Boolean.TRUE);
		}
		try {
			c.publish(topic, message.getPayload(), message.getQos(), false);
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
		SimpleMessageContextBuilder b = new SimpleMessageContextBuilder();
		b.channel(topic);
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
		public void connectionLost(Throwable cause) {
			if (client == owner) {
				Mqtt3Instance.this.connectionLost(cause);
			}
		}

		@Override
		public void messageArrived(String topic, MqttMessage message) {
			// only messages no subscription listener took, left over from an ended subscription;
			// acknowledge and drop
			complete(owner, message);
		}

		@Override
		public void deliveryComplete(IMqttDeliveryToken token) {
			// publish promises complete through their own listeners
		}

	}

}
