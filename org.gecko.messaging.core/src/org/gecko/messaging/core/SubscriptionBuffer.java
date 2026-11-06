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

import static org.gecko.messaging.api.MessagingConstants.*;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.MessagingException;
import org.gecko.messaging.api.dto.SubscriptionDTO;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushEvent.EventType;
import org.osgi.util.pushstream.QueuePolicy;

/**
 * The buffer of a subscription stream: bounded in messages and in payload bytes, with the queue
 * policy of the subscription, and settling what it discards. Messages count from entering the
 * buffer until the stream takes them ({@link #taken}).
 * <p>
 * {@code DISCARD_OLDEST} counts what it discards as dropped and rejects it without re-queue, so
 * that it does not hold a place of the broker's flow control for ever. With a group header it
 * discards whole groups, so that what stays starts with a group start (a key frame).
 */
final class SubscriptionBuffer implements QueuePolicy<Message, BlockingQueue<PushEvent<? extends Message>>> {

	private enum Policy {
		BLOCK, DISCARD_OLDEST, FAIL
	}

	private final int size;
	private final long maxBytes;
	private final Policy policy;
	private final String groupHeader;
	private final AtomicReference<SubscriptionHandle> handle = new AtomicReference<>();
	/** Guarded by this. */
	private int messages;
	/** Guarded by this. */
	private long bytes;
	/** Guarded by this. */
	private long blockedNanos;
	/**
	 * A group lost messages: the rest of it is discarded as it arrives, up to the next group
	 * start. Guarded by this.
	 */
	private boolean skipping;

	private SubscriptionBuffer(int size, long maxBytes, Policy policy, String groupHeader) {
		this.size = size;
		this.maxBytes = maxBytes;
		this.policy = policy;
		this.groupHeader = groupHeader;
	}

	/**
	 * Returns the buffer of a subscription: its own settings, bounded by the instance's.
	 *
	 * @param config the instance configuration
	 * @param defaultPolicy the protocol's default queue policy
	 * @param context the subscription context
	 * @return the buffer
	 * @throws MessagingException if a setting is invalid or above the instance's
	 */
	static SubscriptionBuffer of(MessagingConfiguration config, String defaultPolicy, MessageContext context) {
		Object sizeValue = context.getExtensions().get(EXTENSION_BUFFER_SIZE);
		Object bytesValue = context.getExtensions().get(EXTENSION_BUFFER_BYTES);
		Object policyValue = context.getExtensions().get(EXTENSION_QUEUE_POLICY);
		Object groupValue = context.getExtensions().get(EXTENSION_GROUP_HEADER);
		int size = config.bufferSize();
		long maxBytes = config.bufferBytes();
		if (sizeValue != null) {
			size = (int) bounded(EXTENSION_BUFFER_SIZE, Extensions.number(sizeValue), size);
		}
		if (bytesValue != null) {
			maxBytes = bounded(EXTENSION_BUFFER_BYTES, Extensions.number(bytesValue), maxBytes);
		}
		String name = policyValue != null ? policyValue.toString() : config.queuePolicy(defaultPolicy);
		Policy policy;
		try {
			policy = Policy.valueOf(name.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new MessagingException("Unknown queue policy '" + name + "'", e);
		}
		return new SubscriptionBuffer(size, maxBytes, policy, groupValue == null ? null : groupValue.toString());
	}

	private static long bounded(String key, Long value, long max) {
		if (value == null || value < 1 || value > max) {
			throw new MessagingException(key + " must be between 1 and " + max + " (the instance's limit), not " + value);
		}
		return value;
	}

	/** @return a new, empty queue of the buffer's size; it takes memory as it fills */
	BlockingQueue<PushEvent<? extends Message>> newQueue() {
		return new LinkedBlockingQueue<>(size);
	}

	/** @param h the subscription, once the stream is connected */
	void attach(SubscriptionHandle h) {
		handle.set(h);
	}

	@Override
	public void doOffer(BlockingQueue<PushEvent<? extends Message>> queue, PushEvent<? extends Message> event) throws Exception {
		if (event.getType() != EventType.DATA) {
			queue.put(event);
			return;
		}
		long n = bytes(event.getData());
		synchronized (this) {
			if (groupHeader != null) {
				if (startsGroup(event)) {
					skipping = false;
				} else if (skipping) {
					dropped(event.getData());
					return;
				}
			}
			while (!fits(n) || !queue.offer(event)) {
				switch (policy) {
					case FAIL:
						throw new MessagingException("The buffer of the subscription is full (" + messages + " messages, " + bytes
								+ " bytes)");
					case DISCARD_OLDEST:
						discardOldest(queue);
						if (groupHeader != null && queue.isEmpty() && !startsGroup(event)) {
							// the whole group up to here is gone: this message and the rest of
							// its group are of no use
							skipping = true;
							dropped(event.getData());
							return;
						}
						break;
					default:
						long start = System.nanoTime();
						wait(100);
						blockedNanos += System.nanoTime() - start;
				}
			}
			messages++;
			bytes += n;
		}
	}

	/** A message larger than the byte limit is taken into an empty buffer, alone. */
	private boolean fits(long n) {
		return messages == 0 || bytes + n <= maxBytes;
	}

	private void discardOldest(BlockingQueue<PushEvent<? extends Message>> queue) {
		PushEvent<? extends Message> oldest = queue.poll();
		if (oldest == null) {
			return;
		}
		discarded(oldest);
		if (groupHeader != null) {
			// the rest of the group is of no use without its start
			PushEvent<? extends Message> next;
			while ((next = queue.peek()) != null && !startsGroup(next) && queue.remove(next)) {
				discarded(next);
			}
		}
	}

	private boolean startsGroup(PushEvent<? extends Message> event) {
		if (event.getType() != EventType.DATA) {
			return true;
		}
		Object v = event.getData().getContext() == null ? null : event.getData().getContext().getHeaders().get(groupHeader);
		return Boolean.TRUE.equals(Extensions.bool(v));
	}

	private void discarded(PushEvent<? extends Message> event) {
		if (event.getType() != EventType.DATA) {
			return;
		}
		Message message = event.getData();
		messages--;
		bytes -= bytes(message);
		dropped(message);
	}

	/** Counts and settles a message that does not reach the stream. */
	private void dropped(Message message) {
		SubscriptionHandle h = handle.get();
		if (h != null) {
			h.dropped();
		}
		AcknowledgeSupport ack = AcknowledgeSupport.of(message);
		if (ack != null) {
			// an overload is no reason to hand the message to another receiver
			ack.discarded();
		}
	}

	/**
	 * The stream took a message from the buffer.
	 *
	 * @param message the message
	 * @return the message
	 */
	Message taken(Message message) {
		synchronized (this) {
			messages--;
			bytes -= bytes(message);
			notifyAll();
		}
		AcknowledgeSupport ack = AcknowledgeSupport.of(message);
		if (ack != null) {
			ack.taken();
		}
		return message;
	}

	private static long bytes(Message message) {
		return message.payload().remaining();
	}

	/** Fills in the buffer part of a subscription DTO. */
	synchronized void fill(SubscriptionDTO dto) {
		dto.bufferSize = size;
		dto.bufferBytes = maxBytes;
		dto.bufferedMessages = Math.max(0, messages);
		dto.bufferedBytes = Math.max(0, bytes);
		dto.blockedMillis = TimeUnit.NANOSECONDS.toMillis(blockedNanos);
	}

}
