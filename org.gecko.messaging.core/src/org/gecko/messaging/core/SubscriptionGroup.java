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
package org.gecko.messaging.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One broker subscription feeding several local subscriptions, for protocols that subscribe a
 * channel once however often it is subscribed locally (MQTT, WebSocket).
 * <p>
 * A message goes to every local subscription of a topic, and to one of a queue, in turn. It is
 * completed on the broker once every receiver has decided. The group also keeps the quality of
 * service of the broker subscription: when a local subscription needs more than the broker
 * subscription has, {@link #add} says so, and the protocol subscribes again with it. The quality
 * of service is not lowered when such a subscription ends: receiving more than asked is allowed.
 */
public final class SubscriptionGroup {

	/** What the protocol does with a received message once it is decided. */
	public interface Broker {

		/** Completes (acknowledges) the message on the broker. */
		void complete();

		/** Publishes or puts back a rejected message for another receiver. */
		void requeue();

	}

	private final boolean queue;
	private final Set<SubscriptionHandle> handles = new CopyOnWriteArraySet<>();
	private final AtomicInteger turn = new AtomicInteger();
	/** The quality of service of the broker subscription, -1 before it is made; guarded by this. */
	private int qos = -1;

	/**
	 * Creates a group.
	 *
	 * @param queue {@code true} for a point-to-point channel: one receiver per message
	 */
	public SubscriptionGroup(boolean queue) {
		this.queue = queue;
	}

	/**
	 * Adds a local subscription.
	 *
	 * @param handle the subscription
	 * @param handleQos the quality of service it needs
	 * @return the quality of service to subscribe the broker with now — on the first subscription,
	 *         or when this one needs more — or {@code -1} if the broker subscription serves it
	 */
	public synchronized int add(SubscriptionHandle handle, int handleQos) {
		handles.add(handle);
		return handleQos > qos ? Math.max(handleQos, 0) : -1;
	}

	/**
	 * Records that the broker subscription is made with a quality of service.
	 *
	 * @param subscribed the quality of service the broker subscription has now
	 */
	public synchronized void subscribed(int subscribed) {
		qos = Math.max(qos, subscribed);
	}

	/**
	 * Removes a local subscription.
	 *
	 * @param handle the subscription
	 * @return {@code true} if it was the last one: the broker subscription can go
	 */
	public synchronized boolean remove(SubscriptionHandle handle) {
		handles.remove(handle);
		return handles.isEmpty();
	}

	/** @return {@code true} if the broker subscription exists */
	public synchronized boolean isSubscribed() {
		return qos >= 0;
	}

	/**
	 * Delivers a received message to the local subscriptions.
	 *
	 * @param context the context of the message
	 * @param payload the payload, owned by the group from now on
	 * @param broker completes or re-queues the message on the broker
	 */
	public void dispatch(SimpleMessageContext context, byte[] payload, Broker broker) {
		List<SubscriptionHandle> targets = new ArrayList<>(handles);
		if (queue && targets.size() > 1) {
			// competing receivers: one local subscription gets the message
			targets = List.of(targets.get(Math.floorMod(turn.getAndIncrement(), targets.size())));
		}
		if (targets.isEmpty()) {
			broker.complete();
			return;
		}
		AtomicInteger open = new AtomicInteger(targets.size());
		Runnable done = () -> {
			if (open.decrementAndGet() == 0) {
				broker.complete();
			}
		};
		for (int i = 0; i < targets.size(); i++) {
			SubscriptionHandle h = targets.get(i);
			byte[] own = i == targets.size() - 1 ? payload : payload.clone();
			if (h.needsAcknowledger()) {
				h.deliver(context, own, new Acknowledger() {
					@Override
					public void acknowledge() {
						done.run();
					}

					@Override
					public void reject(boolean requeue) {
						if (requeue) {
							broker.requeue();
						}
						done.run();
					}
				});
			} else {
				h.deliver(context, own, null);
				done.run();
			}
		}
	}

}
