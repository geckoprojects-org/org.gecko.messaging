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
		if (!state.compareAndSet(AcknowledgeType.RECEIVED, AcknowledgeType.REJECTED)) {
			return false;
		}
		try {
			if (acknowledger != null) {
				acknowledger.reject(requeue);
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
