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

import static org.gecko.messaging.api.MessagingConstants.CHANNEL_TYPE_QUEUE;
import static org.gecko.messaging.api.MessagingConstants.EXTENSION_CHANNEL_TYPE;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicLong;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.dto.ChannelDTO;
import org.gecko.messaging.api.dto.SubscriptionDTO;

/**
 * One subscription of an instance, as the protocol sees it: the channel and options to subscribe
 * with, a slot for protocol state, and {@link #deliver(SimpleMessageContext, byte[], Acknowledger)}
 * for every message that arrives.
 */
public final class SubscriptionHandle {

	/** Receives the messages of a subscription. */
	interface Sink {

		/**
		 * Takes a message.
		 *
		 * @param message the message
		 * @return negative to end the subscription, otherwise ignored
		 * @throws Exception on failure, ends the subscription
		 */
		long accept(Message message) throws Exception;

		/**
		 * The subscription ended.
		 *
		 * @param failure the cause, {@code null} for a normal end
		 */
		void closed(Throwable failure);

		/**
		 * Returns the buffer of the sink: the stream reports when it takes a message from it
		 * ({@link AcknowledgeSupport#taken()}). Without one a message counts as taken once
		 * accepted.
		 *
		 * @return the buffer of a subscription stream, {@code null} for an internal sink
		 */
		default SubscriptionBuffer buffer() {
			return null;
		}

		/**
		 * Returns whether the sink reports itself when it has taken a message
		 * ({@link AcknowledgeSupport#taken()}), as a sink with buffer does.
		 *
		 * @return {@code false} if a message counts as taken once accepted
		 */
		default boolean takesItself() {
			return buffer() != null;
		}

	}

	private static final AtomicLong IDS = new AtomicLong();

	private final long id = IDS.incrementAndGet();
	private final AbstractMessagingInstance instance;
	private final SimpleMessageContext context;
	private final Sink sink;
	private final boolean internal;
	private final AtomicLong received = new AtomicLong();
	private final AtomicLong dropped = new AtomicLong();
	/** Messages undecided, or accepted while still in the buffer: settled when the subscription ends. */
	final Set<AcknowledgeSupport> openAcknowledgements = ConcurrentHashMap.newKeySet();
	/** Delivers one message at a time into the sink. */
	final ReentrantLock deliveryLock = new ReentrantLock();
	private volatile boolean connected;
	private volatile boolean closed;
	private volatile Object attachment;

	SubscriptionHandle(AbstractMessagingInstance instance, SimpleMessageContext context, Sink sink, boolean internal) {
		this.instance = instance;
		this.context = context;
		this.sink = sink;
		this.internal = internal;
	}

	/** @return a unique id */
	public long id() {
		return id;
	}

	/** Counts a received message that was dropped. */
	void dropped() {
		dropped.incrementAndGet();
	}

	/** @return whether the subscription is on a point-to-point channel */
	public boolean isQueue() {
		return CHANNEL_TYPE_QUEUE.equals(context.getExtensions().get(EXTENSION_CHANNEL_TYPE));
	}

	/** @return the channel to subscribe to */
	public String channel() {
		return context.getChannel();
	}

	/** @return the channel extension, or {@code null} */
	public String channelExtension() {
		return context.getChannelExtension();
	}

	/** @return the subscription context with all options */
	public SimpleMessageContext context() {
		return context;
	}

	/**
	 * Returns whether the protocol must deliver an {@link Acknowledger} — the subscription
	 * acknowledges itself instead of on receipt.
	 *
	 * @return {@code true} for manual acknowledgement on the broker
	 */
	public boolean needsAcknowledger() {
		return context.getAcknowledgeFilter() != null || context.getAcknowledgeFunction() != null
				|| context.isManualAcknowledge();
	}

	/** @return whether this subscription is internal (reply-to, whiteboard) */
	public boolean isInternal() {
		return internal;
	}

	/** @return the protocol state attached */
	public Object attachment() {
		return attachment;
	}

	/**
	 * Attaches protocol state, for example a consumer tag.
	 *
	 * @param attachment the state
	 */
	public void attach(Object attachment) {
		this.attachment = attachment;
	}

	/** @return whether the subscription is ended */
	public boolean isClosed() {
		return closed;
	}

	/**
	 * Delivers a received message.
	 *
	 * @param messageContext the context of the received message
	 * @param payload the payload; owned by the message from now on
	 * @param acknowledger acknowledges on the broker; {@code null} if the protocol acknowledged
	 *            already or has no acknowledgement
	 */
	public void deliver(SimpleMessageContext messageContext, byte[] payload, Acknowledger acknowledger) {
		if (closed) {
			if (acknowledger != null) {
				// a queue message goes to another receiver; a topic message every receiver has anyway
				acknowledger.reject(isQueue());
			}
			return;
		}
		received.incrementAndGet();
		instance.deliver(this, messageContext, payload, acknowledger);
	}

	/**
	 * The protocol could not keep the subscription (for example the broker cancelled it). Ends it
	 * with the given failure.
	 *
	 * @param failure the cause
	 */
	public void fail(Throwable failure) {
		instance.closeSubscription(this, failure);
	}

	Sink sink() {
		return sink;
	}

	void setConnected(boolean connected) {
		this.connected = connected;
	}

	boolean markClosed() {
		if (closed) {
			return false;
		}
		closed = true;
		connected = false;
		return true;
	}

	SubscriptionDTO toDTO() {
		SubscriptionDTO dto = new SubscriptionDTO();
		dto.channel = channelDTO(channel(), channelExtension(), connected);
		dto.receivedMessages = received.get();
		dto.droppedMessages = dropped.get();
		SubscriptionBuffer buffer = sink.buffer();
		if (buffer != null) {
			buffer.fill(dto);
		}
		dto.receiveWindow = instance.receiveWindow(this);
		return dto;
	}

	static ChannelDTO channelDTO(String name, String extension, boolean connected) {
		ChannelDTO c = new ChannelDTO();
		c.name = name;
		c.extension = extension;
		c.connected = connected;
		return c;
	}

	@Override
	public String toString() {
		return "Subscription[" + id + ", " + channel() + (channelExtension() == null ? "" : "/" + channelExtension()) + "]";
	}

}
