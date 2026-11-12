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
package org.gecko.messaging.amqp;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.gecko.messaging.api.MessagingConstants.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.messaging.api.FeatureNotSupportedException;
import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.MessagingException;
import org.gecko.messaging.core.AbstractMessagingInstance;
import org.gecko.messaging.core.Acknowledger;
import org.gecko.messaging.core.Extensions;
import org.gecko.messaging.core.MessagingConfiguration;
import org.gecko.messaging.core.SimpleMessageContext;
import org.gecko.messaging.core.SimpleMessageContextBuilder;
import org.gecko.messaging.core.SubscriptionHandle;
import org.osgi.util.promise.Deferred;
import org.osgi.util.promise.Promise;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AuthenticationFailureException;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConfirmListener;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.ShutdownSignalException;

/**
 * A messaging instance over AMQP 0-9-1 with the RabbitMQ Java client — one connection per
 * instance.
 * <p>
 * Channels map as follows:
 * <ul>
 * <li>a topic (the default) is a routing key on a topic exchange ({@code exchange}, default
 * {@code amq.topic}); each subscription has its own exclusive, server-named queue bound with the
 * channel, wildcards {@code *} and {@code #}, separator {@code .};</li>
 * <li>a channel with an extension names an exchange, the extension is the routing key;</li>
 * <li>a queue is a named queue on the default exchange; several subscriptions of a queue are
 * competing consumers, the broker hands each message to one of them.</li>
 * </ul>
 * A rejected message is re-queued at most once ({@code redelivered} flag of the broker), as is
 * one redelivered after a lost connection. Publishing with QoS 1 waits for the publisher confirm; QoS 2 does not exist in AMQP and is
 * refused as unsupported feature before anything is sent, as is a queue with a channel extension.
 * Received messages are acknowledged manually (QoS 1, the default) or on delivery (QoS 0); a
 * rejected message is nacked, with re-queue if asked. The instance owns reconnecting: the client's
 * automatic recovery is off, subscriptions are restored by the instance. There is no retain.
 * <p>
 * Configuration, besides the common keys: {@code uri} ({@code amqp://}, {@code amqps://}, with
 * user, password and virtual host; over TLS with the {@code tls.*} keys of the core, and with
 * the host name of the broker checked against its certificate), {@code exchange} (exchange of topics, default
 * {@code amq.topic}), {@code heartbeat} (s, default 30), {@code prefetch} (unacknowledged messages
 * per subscription, default 100, at most {@code bufferSize} and the subscription's own buffer),
 * {@code maxMessageSize} (bytes, default 1 MiB, bodies sent and
 * received), {@code qos} (default publish and subscribe QoS, default 1).
 */
public final class AmqpInstance extends AbstractMessagingInstance {

	private static final Logger LOG = Logger.getLogger(AmqpInstance.class.getName());

	/** The protocol name. */
	public static final String PROTOCOL = "amqp";

	static final List<String> FEATURES = List.of(FEATURE_PUBLISH_SUBSCRIBE, FEATURE_POINT_TO_POINT, FEATURE_WILDCARD,
			FEATURE_CHANNEL_EXTENSION, FEATURE_CONTENT_TYPE, FEATURE_HEADERS, FEATURE_QOS, FEATURE_TIME_TO_LIVE,
			FEATURE_AUTO_CREATE, FEATURE_DURABLE, FEATURE_ACKNOWLEDGE, FEATURE_REPLY_TO, FEATURE_REPLY_TO_MANY,
			FEATURE_REPLY_TO_SUBSCRIPTION, FEATURE_GENERATE_CORRELATION_ID, FEATURE_GENERATE_REPLY_CHANNEL);

	private static final String TOPIC_EXCHANGE_TYPE = "topic";

	private final int defaultQos;
	private final String topicExchange;
	private final int prefetch;
	/** Largest message body sent or received, in bytes. */
	private final int maxMessageSize;
	private volatile Connection connection;
	/** The channel of publishing and declaring; reopened when an error closed it. */
	private Channel publishChannel;
	private final Object publishLock = new Object();
	/** Publishes waiting for their confirm, by delivery tag. */
	private final ConcurrentNavigableMap<Long, Deferred<Void>> unconfirmed = new ConcurrentSkipListMap<>();
	/** Exchanges and queues declared on this connection, so a publish declares only once. */
	private final Set<String> declared = ConcurrentHashMap.newKeySet();

	/**
	 * Creates the instance.
	 *
	 * @param config the configuration
	 */
	public AmqpInstance(MessagingConfiguration config) {
		super(PROTOCOL, config, FEATURES);
		if (config.uri() == null) {
			throw new MessagingException("An AMQP instance needs a 'uri'");
		}
		this.defaultQos = (int) config.number("qos", QOS_AT_LEAST_ONCE);
		if (defaultQos != QOS_AT_MOST_ONCE && defaultQos != QOS_AT_LEAST_ONCE) {
			throw new MessagingException("AMQP offers QoS 0 and 1, not " + defaultQos);
		}
		this.topicExchange = config.string("exchange", "amq.topic");
		this.maxMessageSize = (int) Math.min(Integer.MAX_VALUE, config.number("maxMessageSize", 1 << 20));
		this.prefetch = receiveWindow("prefetch", 100);
	}

	@Override
	protected int receiveWindow() {
		return prefetch;
	}

	@Override
	protected boolean receiveWindowPerSubscription() {
		return true;
	}

	@Override
	protected boolean doConnect(long timeoutMillis) throws Exception {
		ConnectionFactory factory = new ConnectionFactory();
		factory.setUri(config.uri());
		if (tls.isTls()) {
			// for amqps the client's own default trusts every certificate and checks no host name:
			// the stores of the instance or the trust of the JVM instead, and the host name checked
			factory.useSslProtocol(tls.contextOrDefault());
		}
		if (config.username() != null) {
			factory.setUsername(config.username());
		}
		if (config.password() != null) {
			factory.setPassword(config.password());
		}
		factory.setAutomaticRecoveryEnabled(false);
		factory.setTopologyRecoveryEnabled(false);
		factory.setConnectionTimeout((int) timeoutMillis);
		factory.setHandshakeTimeout((int) timeoutMillis);
		factory.setRequestedHeartbeat((int) config.number("heartbeat", 30));
		// the client's own default is 64 MiB
		factory.setMaxInboundMessageBodySize(maxMessageSize);
		Connection c = factory.newConnection(config.clientId());
		c.addShutdownListener(cause -> {
			if (connection == c && !cause.isInitiatedByApplication()) {
				connectionLost(cause);
			}
		});
		declared.clear();
		connection = c;
		synchronized (publishLock) {
			publishChannel = openPublishChannel(c);
		}
		// AMQP has no session to resume
		return false;
	}

	private Channel openPublishChannel(Connection c) throws IOException {
		Channel ch = c.createChannel();
		ch.confirmSelect();
		ch.addConfirmListener(new ConfirmListener() {
			@Override
			public void handleAck(long tag, boolean multiple) {
				settle(tag, multiple, null);
			}

			@Override
			public void handleNack(long tag, boolean multiple) {
				settle(tag, multiple, new MessagingException("The broker refused the message"));
			}
		});
		ch.addShutdownListener(cause -> {
			// publishes in flight on this channel will not be confirmed any more
			MessagingException failure = new MessagingException("The publish channel closed: " + cause.getMessage());
			unconfirmed.values().forEach(d -> d.fail(failure));
			unconfirmed.clear();
		});
		return ch;
	}

	private void settle(long tag, boolean multiple, Throwable failure) {
		if (!multiple) {
			Deferred<Void> d = unconfirmed.remove(tag);
			if (d != null) {
				complete(d, failure);
			}
			return;
		}
		// every tag up to this one; removed one by one, a publish may add a higher tag meanwhile
		Map<Long, Deferred<Void>> done = unconfirmed.headMap(tag, true);
		for (Long t : List.copyOf(done.keySet())) {
			Deferred<Void> d = unconfirmed.remove(t);
			if (d != null) {
				complete(d, failure);
			}
		}
	}

	private static void complete(Deferred<Void> d, Throwable failure) {
		if (failure == null) {
			d.resolve(null);
		} else {
			d.fail(failure);
		}
	}

	@Override
	protected void doDisconnect() {
		Connection c = connection;
		connection = null;
		declared.clear();
		synchronized (publishLock) {
			publishChannel = null;
		}
		if (c == null) {
			return;
		}
		try {
			c.abort(1000);
		} catch (RuntimeException e) {
			LOG.log(Level.FINE, "AMQP close failed", e);
		}
	}

	@Override
	protected long maxMessageSize() {
		return maxMessageSize;
	}

	@Override
	protected boolean isFatal(Throwable failure) {
		return failure instanceof AuthenticationFailureException;
	}

	@Override
	protected String cloudEventHeaderPrefix() {
		// CloudEvents AMQP binding, binary mode: application properties with this prefix
		return "cloudEvents_";
	}

	private Connection connection() {
		Connection c = connection;
		if (c == null || !c.isOpen()) {
			throw new MessagingException("AMQP connection of " + getName() + " is not open");
		}
		return c;
	}

	/** Must be called under {@link #publishLock}. */
	private Channel publishChannel() throws IOException {
		Channel ch = publishChannel;
		if (ch == null || !ch.isOpen()) {
			// an error such as publishing to a missing exchange closes the channel
			ch = openPublishChannel(connection());
			publishChannel = ch;
		}
		return ch;
	}

	private int qos(MessageContext context) {
		int q = Extensions.qos(context);
		return q < 0 ? defaultQos : q;
	}

	@Override
	protected void checkContext(MessageContext context, boolean publish) {
		if (Extensions.qos(context) == QOS_EXACTLY_ONCE) {
			throw new FeatureNotSupportedException(FEATURE_QOS, "AMQP offers at most once and at least once, not exactly once");
		}
		if (isQueue(context) && context.getChannelExtension() != null) {
			throw new FeatureNotSupportedException(FEATURE_CHANNEL_EXTENSION, "A queue has no channel extension");
		}
	}

	private static boolean isQueue(MessageContext context) {
		return CHANNEL_TYPE_QUEUE.equals(context.getExtensions().get(EXTENSION_CHANNEL_TYPE));
	}

	private static boolean flag(MessageContext context, String extension) {
		return Boolean.TRUE.equals(Extensions.bool(context.getExtensions().get(extension)));
	}

	/** Declares an exchange or queue if the context asks for it, once per connection. */
	private void declare(Channel ch, MessageContext context, String name, boolean queue) throws IOException {
		if (!flag(context, EXTENSION_AUTO_CREATE) || !declared.add((queue ? "q:" : "x:") + name)) {
			return;
		}
		boolean durable = flag(context, EXTENSION_DURABLE);
		try {
			if (queue) {
				ch.queueDeclare(name, durable, false, false, null);
			} else {
				ch.exchangeDeclare(name, TOPIC_EXCHANGE_TYPE, durable);
			}
		} catch (IOException | RuntimeException e) {
			declared.remove((queue ? "q:" : "x:") + name);
			throw e;
		}
	}

	// ------------------------------------------------------------------ publish

	@Override
	protected Promise<Void> doPublish(SimpleMessageContext context, ByteBuffer payload) throws Exception {
		byte[] bytes = new byte[payload.remaining()];
		payload.get(bytes);
		int qos = qos(context);
		AMQP.BasicProperties props = properties(context);
		boolean queue = isQueue(context);
		String extension = context.getChannelExtension();
		Deferred<Void> deferred = promises.deferred();
		synchronized (publishLock) {
			Channel ch = publishChannel();
			String exchange;
			String routingKey;
			if (queue) {
				declare(ch, context, context.getChannel(), true);
				exchange = "";
				routingKey = context.getChannel();
			} else if (extension != null) {
				declare(ch, context, context.getChannel(), false);
				exchange = context.getChannel();
				routingKey = extension;
			} else {
				exchange = topicExchange;
				routingKey = context.getChannel();
			}
			long tag = ch.getNextPublishSeqNo();
			if (qos == QOS_AT_LEAST_ONCE) {
				unconfirmed.put(tag, deferred);
			}
			try {
				ch.basicPublish(exchange, routingKey, props, bytes);
			} catch (IOException | RuntimeException e) {
				unconfirmed.remove(tag);
				throw e;
			}
		}
		if (qos == QOS_AT_MOST_ONCE) {
			deferred.resolve(null);
		}
		return deferred.getPromise();
	}

	private static AMQP.BasicProperties properties(SimpleMessageContext context) {
		AMQP.BasicProperties.Builder b = new AMQP.BasicProperties.Builder();
		b.contentType(context.getContentType());
		b.contentEncoding(context.getContentEncoding());
		b.correlationId(context.getCorrelationId());
		b.replyTo(context.getReplyToChannel());
		b.deliveryMode(flag(context, EXTENSION_DURABLE) ? 2 : 1);
		Long ttl = Extensions.number(context.getExtensions().get(EXTENSION_TIME_TO_LIVE));
		if (ttl != null) {
			b.expiration(Long.toString(Math.max(0, ttl)));
		}
		if (!context.getHeaders().isEmpty()) {
			Map<String, Object> headers = new HashMap<>();
			context.getHeaders().forEach((k, v) -> headers.put(k, headerValue(v)));
			b.headers(headers);
		}
		return b.build();
	}

	/** The client encodes only certain types; anything else travels as text. */
	private static Object headerValue(Object v) {
		if (v == null || v instanceof String || v instanceof Integer || v instanceof Long || v instanceof Boolean
				|| v instanceof Double || v instanceof Float || v instanceof Short || v instanceof Byte
				|| v instanceof byte[]) {
			return v;
		}
		return String.valueOf(v);
	}

	// ------------------------------------------------------------------ subscribe

	@Override
	protected void doSubscribe(SubscriptionHandle handle) throws Exception {
		MessageContext context = handle.context();
		int qos = qos(context);
		boolean manual = qos != QOS_AT_MOST_ONCE || handle.needsAcknowledger();
		boolean queue = isQueue(context);
		String extension = handle.channelExtension();
		Channel ch = connection().createChannel();
		try {
			// at most the subscription's own buffer: the broker stops sending before it is full
			ch.basicQos(receiveWindow(handle));
			String source;
			if (queue) {
				declare(ch, context, handle.channel(), true);
				source = handle.channel();
			} else {
				String exchange = extension != null ? handle.channel() : topicExchange;
				if (extension != null) {
					declare(ch, context, exchange, false);
				}
				source = ch.queueDeclare("", false, true, true, null).getQueue();
				ch.queueBind(source, exchange, extension != null ? extension : handle.channel());
			}
			ch.basicConsume(source, !manual, new Consumer(ch, handle, manual));
			handle.attach(ch);
		} catch (IOException | RuntimeException e) {
			close(ch);
			throw e;
		}
	}

	@Override
	protected void doUnsubscribe(SubscriptionHandle handle) {
		if (handle.attachment() instanceof Channel ch) {
			close(ch);
		}
	}

	private static void close(Channel ch) {
		try {
			if (ch.isOpen()) {
				ch.close();
			}
		} catch (IOException | TimeoutException | RuntimeException e) {
			LOG.log(Level.FINE, "AMQP channel close failed", e);
		}
	}

	private final class Consumer extends DefaultConsumer {

		private final SubscriptionHandle handle;
		private final boolean manual;

		Consumer(Channel channel, SubscriptionHandle handle, boolean manual) {
			super(channel);
			this.handle = handle;
			this.manual = manual;
		}

		@Override
		public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties props, byte[] body) {
			SimpleMessageContext ctx = context(handle, envelope, props);
			long tag = envelope.getDeliveryTag();
			if (!manual) {
				handle.deliver(ctx, body, null);
				return;
			}
			// acknowledged once the subscription has taken it from its buffer and accepted it: until
			// then it counts against the prefetch, so a slow subscriber throttles the broker
			handle.deliver(ctx, body, new Acknowledger() {
				@Override
				public void acknowledge() {
					ack(tag);
				}

				@Override
				public void reject(boolean requeue) {
					// the broker flags a message it delivers again: re-queued once, it is dropped
					nack(tag, requeue && !envelope.isRedeliver());
				}
			});
		}

		private void ack(long tag) {
			Channel ch = getChannel();
			try {
				synchronized (ch) {
					ch.basicAck(tag, false);
				}
			} catch (IOException | RuntimeException e) {
				LOG.log(Level.FINE, "AMQP acknowledgement failed", e);
			}
		}

		private void nack(long tag, boolean requeue) {
			Channel ch = getChannel();
			try {
				synchronized (ch) {
					ch.basicNack(tag, false, requeue);
				}
			} catch (IOException | RuntimeException e) {
				LOG.log(Level.FINE, "AMQP reject failed", e);
			}
		}

		@Override
		public void handleCancel(String consumerTag) {
			// the broker ended the consumer, e.g. the queue was deleted
			handle.fail(new MessagingException("The broker cancelled the subscription of " + handle.channel()));
		}

		@Override
		public void handleShutdownSignal(String consumerTag, ShutdownSignalException sig) {
			// a lost connection is handled by the instance, which restores the subscription
		}

	}

	/**
	 * A received header value as the context takes it: String, Number or Boolean. Other AMQP field
	 * types (timestamps, tables, arrays, byte arrays) travel as text instead of failing the
	 * delivery.
	 */
	static Object headerOf(Object v) {
		if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) {
			return v;
		}
		if (v instanceof byte[] bytes) {
			return new String(bytes, UTF_8);
		}
		return String.valueOf(v);
	}

	private static SimpleMessageContext context(SubscriptionHandle handle, Envelope envelope, AMQP.BasicProperties p) {
		SimpleMessageContextBuilder b = new SimpleMessageContextBuilder();
		if (handle.channelExtension() != null) {
			b.channel(handle.channel(), envelope.getRoutingKey());
		} else if (isQueue(handle.context())) {
			b.channel(handle.channel());
		} else {
			// the concrete routing key, also for a wildcard subscription
			b.channel(envelope.getRoutingKey());
		}
		if (p != null) {
			b.contentType(p.getContentType());
			b.contentEncoding(p.getContentEncoding());
			b.correlationId(p.getCorrelationId());
			b.replyTo(p.getReplyTo());
			if (p.getHeaders() != null) {
				p.getHeaders().forEach((k, v) -> b.header(k, headerOf(v)));
			}
		}
		b.extensionEntry(EXTENSION_QOS, handle.context().getExtensions().getOrDefault(EXTENSION_QOS, QOS_AT_LEAST_ONCE));
		return b.buildSimple();
	}

}
