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
package org.gecko.messaging.core.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.gecko.messaging.api.MessagingConstants;
import org.gecko.messaging.core.Acknowledger;
import org.gecko.messaging.core.Features;
import org.gecko.messaging.core.SimpleMessageContext;
import org.gecko.messaging.core.SimpleMessageContextBuilder;
import org.gecko.messaging.core.SubscriptionHandle;

/**
 * A broker in the JVM. Topics follow MQTT wildcard rules ({@code +}, {@code #}); queues deliver
 * each message to one subscriber, round robin, and re-queue a rejected message once on request.
 * Deliveries run on one thread per broker, like a network would decouple them.
 * <p>
 * As the reference of every feature: QoS 1 and 2 are met by an in-process delivery that does not
 * lose messages; a time-to-live applies to the kept message, the retained one (others are
 * delivered at once); durable channels last as long as the broker, there is nothing to persist.
 */
public final class InMemoryBroker {

	private static final Map<String, InMemoryBroker> BROKERS = new ConcurrentHashMap<>();

	private final String name;
	private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
	private final Map<String, Envelope> retained = new ConcurrentHashMap<>();
	private final AtomicInteger roundRobin = new AtomicInteger();
	private final ExecutorService delivery;
	private volatile boolean available = true;

	/**
	 * A published message.
	 *
	 * @param expires {@link System#nanoTime()} after which a kept (retained) message is gone,
	 *            {@code Long.MAX_VALUE} without time-to-live
	 */
	private record Envelope(SimpleMessageContext context, byte[] payload, long expires) {

		boolean expired() {
			return expires != Long.MAX_VALUE && System.nanoTime() - expires > 0;
		}
	}

	/** A client connection. */
	final class Connection {

		private final InMemoryInstance owner;
		private final Map<Long, SubscriptionHandle> subscriptions = new ConcurrentHashMap<>();
		private volatile boolean open = true;

		Connection(InMemoryInstance owner) {
			this.owner = owner;
		}

		void subscribe(SubscriptionHandle handle) {
			if (!open) {
				throw new IllegalStateException("Connection closed");
			}
			subscriptions.put(handle.id(), handle);
			if (!isQueue(handle.context())) {
				// an expired retained message is gone for every later subscriber
				retained.values().removeIf(Envelope::expired);
				for (Map.Entry<String, Envelope> e : retained.entrySet()) {
					if (matches(handle.channel(), e.getKey())) {
						delivery.execute(() -> deliver(handle, e.getValue(), 0));
					}
				}
			}
		}

		void unsubscribe(SubscriptionHandle handle) {
			subscriptions.remove(handle.id());
		}

		void publish(SimpleMessageContext context, byte[] payload) {
			if (!open) {
				throw new IllegalStateException("Connection closed");
			}
			Long ttl = Features.number(context.getExtensions().get(MessagingConstants.EXTENSION_TIME_TO_LIVE));
			// messages are delivered at once; the time-to-live counts for what is kept, the retained message
			long expires = ttl == null ? Long.MAX_VALUE : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, ttl));
			Envelope e = new Envelope(context, payload, expires);
			if (!isQueue(context) && Boolean.TRUE.equals(Features.bool(context.getExtensions().get(MessagingConstants.EXTENSION_RETAIN)))) {
				if (payload.length == 0) {
					retained.remove(context.getChannel());
				} else {
					retained.put(context.getChannel(), e);
				}
			}
			delivery.execute(() -> route(e, 0));
		}

		void close() {
			open = false;
			subscriptions.clear();
			connections.remove(this);
		}

	}

	private InMemoryBroker(String name) {
		this.name = name;
		this.delivery = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "memory-broker-" + name);
			t.setDaemon(true);
			return t;
		});
	}

	/**
	 * Returns the broker of the given name, creating it on first use.
	 *
	 * @param name the broker name
	 * @return the broker
	 */
	public static InMemoryBroker get(String name) {
		return BROKERS.computeIfAbsent(name, InMemoryBroker::new);
	}

	/**
	 * Removes a broker, dropping its connections and retained messages.
	 *
	 * @param name the broker name
	 */
	public static void remove(String name) {
		InMemoryBroker b = BROKERS.remove(name);
		if (b != null) {
			b.dropConnections();
			b.delivery.shutdownNow();
		}
	}

	/** @return the broker name */
	public String name() {
		return name;
	}

	/**
	 * Makes the broker reachable or not. When not, all connections drop and connects fail.
	 *
	 * @param available whether the broker is reachable
	 */
	public void setAvailable(boolean available) {
		this.available = available;
		if (!available) {
			dropConnections();
		}
	}

	/** Drops all connections, as a broker restart would. */
	public void dropConnections() {
		for (Connection c : new ArrayList<>(connections)) {
			c.close();
			c.owner.lost(new IllegalStateException("Broker " + name + " dropped the connection"));
		}
	}

	/** @return the number of open connections */
	public int connectionCount() {
		return connections.size();
	}

	Connection connect(InMemoryInstance owner) {
		if (!available) {
			throw new IllegalStateException("Broker " + name + " is not available");
		}
		Connection c = new Connection(owner);
		connections.add(c);
		return c;
	}

	private void route(Envelope e, int redelivery) {
		String channel = e.context().getChannel();
		if (isQueue(e.context())) {
			List<SubscriptionHandle> candidates = new ArrayList<>();
			for (Connection c : connections) {
				for (SubscriptionHandle h : c.subscriptions.values()) {
					if (isQueue(h.context()) && channel.equals(h.channel())) {
						candidates.add(h);
					}
				}
			}
			if (!candidates.isEmpty()) {
				deliver(candidates.get(Math.floorMod(roundRobin.getAndIncrement(), candidates.size())), e, redelivery);
			}
			return;
		}
		for (Connection c : connections) {
			for (SubscriptionHandle h : c.subscriptions.values()) {
				if (!isQueue(h.context()) && matches(h.channel(), channel)
						&& (h.channelExtension() == null || h.channelExtension().equals(e.context().getChannelExtension()))) {
					deliver(h, e, redelivery);
				}
			}
		}
	}

	private void deliver(SubscriptionHandle h, Envelope e, int redelivery) {
		SimpleMessageContext ctx = (SimpleMessageContext) new SimpleMessageContextBuilder().withContext(e.context())
				.extensionEntry(MessagingConstants.EXTENSION_RETAIN, null).buildContext();
		Acknowledger ack = null;
		if (h.needsAcknowledger()) {
			ack = new Acknowledger() {
				@Override
				public void acknowledge() {
					// nothing to forget
				}

				@Override
				public void reject(boolean requeue) {
					if (requeue && redelivery < Acknowledger.MAX_REQUEUES) {
						delivery.execute(() -> route(e, redelivery + 1));
					}
				}
			};
		}
		h.deliver(ctx, e.payload().clone(), ack);
	}

	private static boolean isQueue(SimpleMessageContext ctx) {
		return MessagingConstants.CHANNEL_TYPE_QUEUE.equals(ctx.getExtensions().get(MessagingConstants.EXTENSION_CHANNEL_TYPE));
	}

	/**
	 * MQTT topic matching.
	 *
	 * @param filter the subscription filter, may contain {@code +} and {@code #}
	 * @param topic the topic of the message
	 * @return {@code true} if the filter matches
	 */
	public static boolean matches(String filter, String topic) {
		String[] f = filter.split("/", -1);
		String[] t = topic.split("/", -1);
		for (int i = 0; i < f.length; i++) {
			if (f[i].equals("#")) {
				return true;
			}
			if (i >= t.length) {
				return false;
			}
			if (!f[i].equals("+") && !f[i].equals(t[i])) {
				return false;
			}
		}
		return f.length == t.length;
	}

}
