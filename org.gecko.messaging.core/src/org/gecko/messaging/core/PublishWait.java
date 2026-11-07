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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.gecko.messaging.api.MessagingException;
import org.osgi.util.promise.Deferred;
import org.osgi.util.promise.Promise;

/**
 * Single publishes waiting for the connection ({@code publishAwaitConnection}, off by default): a
 * publish while the instance is not connected waits at most that long instead of failing at once,
 * bounded by {@code publishAwaitMessages} and {@code publishAwaitBytes}; beyond the bounds it
 * fails at once. Once connected the waiting messages go out in their order, within the send window
 * of the instance, before any later publish. No thread of its own: the timeouts run on the
 * scheduler, sending on the thread that connected or that made room in the window.
 */
final class PublishWait {

	private static final class Waiting {

		final SimpleMessageContext context;
		final ByteBuffer payload;
		final Deferred<Void> result;
		ScheduledFuture<?> timeout;

		Waiting(SimpleMessageContext context, ByteBuffer payload, Deferred<Void> result) {
			this.context = context;
			this.payload = payload;
			this.result = result;
		}

	}

	private final AbstractMessagingInstance instance;
	private final long timeout;
	private final int maxMessages;
	private final long maxBytes;
	/** Guarded by this. */
	private final ArrayDeque<Waiting> waiting = new ArrayDeque<>();
	/** Guarded by this. */
	private long bytes;
	/** Guarded by this: one thread sends at a time, so the order holds. */
	private boolean sending;
	/** Guarded by this: set by {@link #failAll(String)}, no message waits afterwards. */
	private String closed;

	/**
	 * @param instance the instance
	 * @param config the configuration
	 */
	PublishWait(AbstractMessagingInstance instance, MessagingConfiguration config) {
		this.instance = instance;
		this.timeout = Math.max(0, config.number("publishAwaitConnection", 0));
		this.maxMessages = (int) Math.max(1, config.number("publishAwaitMessages", 100));
		this.maxBytes = Math.max(1, config.number("publishAwaitBytes", 1L << 20));
	}

	/** @return whether publishes wait for the connection */
	boolean enabled() {
		return timeout > 0;
	}

	/** @return whether messages wait: a new publish must queue behind them */
	synchronized boolean hasWaiting() {
		return !waiting.isEmpty();
	}

	/** @return the number of waiting messages */
	synchronized int size() {
		return waiting.size();
	}

	/**
	 * Lets a message wait for the connection.
	 *
	 * @param context the context, checked
	 * @param payload the payload
	 * @return the promise of the hand-over
	 */
	Promise<Void> offer(SimpleMessageContext context, ByteBuffer payload) {
		Waiting w = new Waiting(context, payload, instance.promises().deferred());
		synchronized (this) {
			if (closed != null) {
				return instance.promises().failed(new MessagingException("Messaging instance " + instance.getName() + " " + closed));
			}
			if (waiting.size() >= maxMessages || bytes + payload.remaining() > maxBytes) {
				return instance.promises().failed(new MessagingException("Messaging instance " + instance.getName()
						+ " is not connected (" + instance.getConnectionState() + ") and " + waiting.size() + " messages of "
						+ bytes + " bytes wait already"));
			}
			w.timeout = instance.scheduler().schedule(() -> expire(w), timeout, TimeUnit.MILLISECONDS);
			waiting.add(w);
			bytes += payload.remaining();
		}
		send();
		return w.result.getPromise();
	}

	private void expire(Waiting w) {
		synchronized (this) {
			if (!waiting.remove(w)) {
				return; // sent meanwhile
			}
			bytes -= w.payload.remaining();
		}
		w.result.fail(new MessagingException("Messaging instance " + instance.getName() + " did not connect within "
				+ timeout + " ms (" + instance.getConnectionState() + ")"));
	}

	/**
	 * Sends the waiting messages while the instance is connected and the send window has room.
	 * Called on connect, when a message waits and when the window has room again.
	 */
	void send() {
		synchronized (this) {
			if (sending) {
				return;
			}
			sending = true;
		}
		while (true) {
			Waiting w;
			synchronized (this) {
				if (waiting.isEmpty() || !instance.isConnected() || !instance.tryAcquireSend()) {
					// under the same lock as offer: a message added after this sees sending=false
					sending = false;
					return;
				}
				w = waiting.poll();
				bytes -= w.payload.remaining();
			}
			w.timeout.cancel(false);
			Promise<Void> p = instance.send(w.context, w.payload);
			p.onResolve(instance::releaseSend);
			w.result.resolveWith(p);
		}
	}

	/**
	 * Fails every waiting message.
	 *
	 * @param reason the reason
	 */
	void failAll(String reason) {
		List<Waiting> all;
		synchronized (this) {
			closed = reason;
			all = new ArrayList<>(waiting);
			waiting.clear();
			bytes = 0;
		}
		for (Waiting w : all) {
			w.timeout.cancel(false);
			w.result.fail(new MessagingException("Messaging instance " + instance.getName() + " " + reason));
		}
	}

}
