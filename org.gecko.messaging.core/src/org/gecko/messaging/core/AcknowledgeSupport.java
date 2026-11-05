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
import org.gecko.messaging.api.acknowledge.AcknowledgeType;

/**
 * The {@link AcknowledgeHandler} of one received message: decides once, then tells the protocol
 * and the post consumers.
 */
final class AcknowledgeSupport implements AcknowledgeHandler {

	private static final Logger LOG = Logger.getLogger(AcknowledgeSupport.class.getName());

	private final AtomicReference<AcknowledgeType> state;
	private final Acknowledger acknowledger;
	private final boolean requeue;
	private final Consumer<Message> postAcknowledge;
	private final Consumer<Message> postReject;
	private final Runnable onDecided;
	private volatile Message message;

	AcknowledgeSupport(Acknowledger acknowledger, boolean requeue, Consumer<Message> postAcknowledge,
			Consumer<Message> postReject, Runnable onDecided) {
		this.state = new AtomicReference<>(AcknowledgeType.RECEIVED);
		this.acknowledger = acknowledger;
		this.requeue = requeue;
		this.postAcknowledge = postAcknowledge;
		this.postReject = postReject;
		this.onDecided = onDecided;
	}

	/**
	 * Makes the acknowledgement decision of a received message as its subscription asks: by
	 * function, by filter, manually in the stream, or at once.
	 *
	 * @param h the subscription
	 * @param message the decoded message
	 * @param acknowledger the protocol's side, {@code null} if it has none
	 * @return the message for the stream, carrying its handler if it has one, or {@code null} if
	 *         it is decided not to reach the stream
	 */
	static SimpleMessage decide(SubscriptionHandle h, SimpleMessage message, Acknowledger acknowledger) {
		SimpleMessageContext sub = h.context();
		if (acknowledger == null && sub.getPostAcknowledge() == null && sub.getPostReject() == null && !h.needsAcknowledger()) {
			return message;
		}
		AcknowledgeSupport[] self = new AcknowledgeSupport[1];
		// on a topic a message published again would reach every subscriber, not just this one
		AcknowledgeSupport ack = new AcknowledgeSupport(acknowledger, sub.isRequeueOnReject() && h.isQueue(), sub.getPostAcknowledge(),
				sub.getPostReject(), () -> h.openAcknowledgements.remove(self[0]));
		self[0] = ack;
		SimpleMessage m = message.withContext(((SimpleMessageContext) message.getContext()).withAcknowledgeHandler(ack));
		ack.setMessage(m);
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
			if (sub.isManualAcknowledge()) {
				h.openAcknowledgements.add(ack);
			} else {
				ack.acknowledge();
			}
			return m;
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Acknowledgement decision failed on " + h + ", rejecting without re-queue", e);
			ack.rejectFailed();
			return null;
		}
	}

	void setMessage(Message message) {
		this.message = message;
	}

	@Override
	public boolean acknowledge() {
		if (!state.compareAndSet(AcknowledgeType.RECEIVED, AcknowledgeType.ACKNOWLEDGED)) {
			return false;
		}
		try {
			if (acknowledger != null) {
				acknowledger.acknowledge();
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

	private void decided(Consumer<Message> post) {
		if (onDecided != null) {
			onDecided.run();
		}
		if (post != null) {
			try {
				post.accept(message);
			} catch (RuntimeException e) {
				LOG.log(Level.WARNING, "A post acknowledgement consumer failed", e);
			}
		}
	}

	@Override
	public AcknowledgeType getAcknowledgeState() {
		return state.get();
	}

}
