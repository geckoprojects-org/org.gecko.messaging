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

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.acknowledge.AcknowledgeHandler;
import org.gecko.messaging.api.acknowledge.AcknowledgeMessageContext;
import org.gecko.messaging.api.acknowledge.AcknowledgeType;

/**
 * The {@link AcknowledgeHandler} of one received message: decides once, then tells the protocol
 * and the post consumers.
 * <p>
 * The protocol hears of an acceptance only once the message has left the subscription buffer
 * ({@link #taken()}): until then the message holds a place of the broker's flow control
 * ({@code receiveMaximum}, {@code prefetch}), so that a slow subscriber throttles the broker
 * instead of blocking the protocol's threads. Without an explicit decision a message is accepted
 * when it is taken. A message the buffer discards or that is still in the buffer when the
 * subscription ends is settled as well ({@link #discarded()}, {@link #abandoned()}).
 */
final class AcknowledgeSupport implements AcknowledgeHandler {

	private static final Logger LOG = Logger.getLogger(AcknowledgeSupport.class.getName());

	private final AtomicReference<AcknowledgeType> state;
	private final Acknowledger acknowledger;
	private final boolean requeue;
	private final boolean queue;
	private final Consumer<Message> postAcknowledge;
	private final Consumer<Message> postReject;
	private final SubscriptionHandle handle;
	private volatile Message message;
	/** Accept when taken: neither function, filter nor manual decides. */
	private boolean acceptWhenTaken;
	/** Guarded by this. */
	private boolean taken;
	/** Accepted while still in the buffer: the protocol is told when taken. Guarded by this. */
	private boolean acceptPending;

	private AcknowledgeSupport(SubscriptionHandle handle, Acknowledger acknowledger) {
		SimpleMessageContext sub = handle.context();
		this.state = new AtomicReference<>(AcknowledgeType.RECEIVED);
		this.handle = handle;
		this.acknowledger = acknowledger;
		this.queue = handle.isQueue();
		// on a topic a message published again would reach every subscriber, not just this one
		this.requeue = sub.isRequeueOnReject() && queue;
		this.postAcknowledge = sub.getPostAcknowledge();
		this.postReject = sub.getPostReject();
	}

	/**
	 * Makes the acknowledgement decision of a received message as its subscription asks: by
	 * function or filter at once, manually in the stream, or by default when the stream takes it.
	 *
	 * @param h the subscription
	 * @param message the decoded message
	 * @param acknowledger the protocol's side, {@code null} if it has acknowledged already
	 * @return the message for the stream, carrying its handler if it has one, or {@code null} if
	 *         it is decided not to reach the stream
	 */
	static SimpleMessage decide(SubscriptionHandle h, SimpleMessage message, Acknowledger acknowledger) {
		SimpleMessageContext sub = h.context();
		if (acknowledger == null && sub.getPostAcknowledge() == null && sub.getPostReject() == null && !h.needsAcknowledger()) {
			return message;
		}
		AcknowledgeSupport ack = new AcknowledgeSupport(h, acknowledger);
		SimpleMessage m = message.withContext(((SimpleMessageContext) message.getContext()).withAcknowledgeHandler(ack));
		ack.message = m;
		h.openAcknowledgements.add(ack);
		try {
			if (sub.getAcknowledgeFunction() != null) {
				sub.getAcknowledgeFunction().accept(m);
				ack.acknowledge(); // undecided counts as accepted
				return ack.getAcknowledgeState() == AcknowledgeType.ACKNOWLEDGED ? m : null;
			}
			if (sub.getAcknowledgeFilter() != null) {
				if (sub.getAcknowledgeFilter().test(m)) {
					ack.acknowledge();
					return m;
				}
				ack.reject();
				return null;
			}
			ack.acceptWhenTaken = !sub.isManualAcknowledge();
			return m;
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Acknowledgement decision failed on " + h + ", rejecting without re-queue", e);
			ack.rejectFailed();
			return null;
		}
	}

	/**
	 * Returns the handler of a message, if it has one of this kind.
	 *
	 * @param message the message
	 * @return the handler, or {@code null}
	 */
	static AcknowledgeSupport of(Message message) {
		return message.getContext() instanceof AcknowledgeMessageContext ac
				&& ac.getAcknowledgeHandler() instanceof AcknowledgeSupport s ? s : null;
	}

	/** The stream took the message from the subscription buffer. */
	void taken() {
		boolean accept;
		boolean tell;
		synchronized (this) {
			if (taken) {
				return;
			}
			taken = true;
			accept = acceptWhenTaken;
			tell = acceptPending;
			acceptPending = false;
		}
		if (accept) {
			acknowledge();
		} else if (tell) {
			tellAccepted();
		}
		settledIfDone();
	}

	/** The subscription buffer discarded the message: dropped, without re-queue. */
	void discarded() {
		if (!rejectFailed()) {
			tellPendingAcceptance();
		}
	}

	/**
	 * The subscription ended with the message undecided or still in its buffer. A message the
	 * application never saw goes back to a queue; one it rejects is treated as it asks.
	 */
	void abandoned() {
		boolean seen;
		synchronized (this) {
			seen = taken;
		}
		boolean decided = seen && !acceptWhenTaken ? reject() : reject(queue);
		if (!decided) {
			tellPendingAcceptance();
		}
	}

	@Override
	public boolean acknowledge() {
		if (!state.compareAndSet(AcknowledgeType.RECEIVED, AcknowledgeType.ACKNOWLEDGED)) {
			return false;
		}
		try {
			boolean now;
			synchronized (this) {
				now = taken;
				acceptPending = !taken;
			}
			if (now) {
				tellAccepted();
			}
		} finally {
			decided(postAcknowledge);
		}
		return true;
	}

	@Override
	public boolean reject() {
		return reject(requeue);
	}

	/**
	 * Rejects without re-queue, whatever the subscription asks: a message the decision failed on
	 * would come back and fail again at once.
	 *
	 * @return {@code true} if this call decided
	 */
	boolean rejectFailed() {
		return reject(false);
	}

	private boolean reject(boolean requeueIt) {
		if (!state.compareAndSet(AcknowledgeType.RECEIVED, AcknowledgeType.REJECTED)) {
			return false;
		}
		try {
			if (acknowledger != null) {
				acknowledger.reject(requeueIt);
			}
		} finally {
			decided(postReject);
		}
		return true;
	}

	/** An acceptance the buffer still held back is told now: the decision stands. */
	private void tellPendingAcceptance() {
		boolean tell;
		synchronized (this) {
			tell = acceptPending;
			acceptPending = false;
			taken = true;
		}
		if (tell) {
			tellAccepted();
		}
		settledIfDone();
	}

	private void tellAccepted() {
		if (acknowledger != null) {
			acknowledger.acknowledge();
		}
	}

	private void decided(Consumer<Message> post) {
		settledIfDone();
		if (post != null) {
			try {
				post.accept(message);
			} catch (RuntimeException e) {
				LOG.log(Level.WARNING, "A post acknowledgement consumer failed", e);
			}
		}
	}

	/** Leaves the open set once decided and told: nothing is left to do on close. */
	private void settledIfDone() {
		boolean done;
		synchronized (this) {
			done = state.get() != AcknowledgeType.RECEIVED && !acceptPending;
		}
		if (done) {
			handle.openAcknowledgements.remove(this);
		}
	}

	@Override
	public AcknowledgeType getAcknowledgeState() {
		return state.get();
	}

}
