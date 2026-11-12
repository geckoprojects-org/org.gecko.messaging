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
 * <p>
 * When its stream is closed the buffer is {@link #release() released}: a delivery waiting for
 * room returns, and what is buffered or still arrives is settled as abandoned. Otherwise a
 * delivery blocked on a full buffer would hold the protocol's thread for ever, as nothing takes
 * from the queue of a closed stream.
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
	/** The messages the buffer dropped. Guarded by this. */
	private long dropped;
	/** The stream is closed: nothing takes from the queue any more. Guarded by this. */
	private boolean released;
	/** The queue of the stream, once made. */
	private volatile BlockingQueue<PushEvent<? extends Message>> queue;
	/**
	 * A group lost messages: the rest of it is discarded as it arrives, up to the next group
	 * start. Guarded by this.
	 */
	private boolean skipping;
	/**
	 * Messages were discarded from the queue: what the stream takes next is dropped up to the next
	 * group start. The stream takes from the queue without this lock, so it may get hold of a
	 * message of a group while the messages before it are discarded. Guarded by this.
	 */
	private boolean gap;

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
		BlockingQueue<PushEvent<? extends Message>> q = new LinkedBlockingQueue<>(size);
		queue = q;
		return q;
	}

	/** @param h the subscription, once the stream is connected */
	void attach(SubscriptionHandle h) {
		handle.set(h);
	}

	@Override
	public void doOffer(BlockingQueue<PushEvent<? extends Message>> queue, PushEvent<? extends Message> event) throws Exception {
		if (event.getType() != EventType.DATA) {
			synchronized (this) {
				// after release the queue is empty: the end still reaches the stream
				while (!queue.offer(event)) {
					if (released) {
						return;
					}
					wait(100);
				}
			}
			return;
		}
		long n = bytes(event.getData());
		synchronized (this) {
			if (released) {
				abandon(event.getData());
				return;
			}
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
						if (released) {
							abandon(event.getData());
							return;
						}
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
			gap = true;
			// the rest of the group is of no use without its start
			PushEvent<? extends Message> next;
			while ((next = queue.peek()) != null && !startsGroup(next) && queue.remove(next)) {
				discarded(next);
			}
		}
	}

	private boolean startsGroup(PushEvent<? extends Message> event) {
		return event.getType() != EventType.DATA || startsGroup(event.getData());
	}

	private boolean startsGroup(Message message) {
		Object v = message.getContext() == null ? null : message.getContext().getHeaders().get(groupHeader);
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

	/**
	 * The stream of the buffer is closed: a delivery waiting for room returns, what is buffered
	 * and what still arrives is settled as abandoned.
	 */
	void release() {
		BlockingQueue<PushEvent<? extends Message>> q = queue;
		synchronized (this) {
			if (released) {
				return;
			}
			released = true;
			notifyAll();
			if (q == null) {
				return;
			}
			PushEvent<? extends Message> event;
			while ((event = q.poll()) != null) {
				if (event.getType() == EventType.DATA) {
					messages--;
					bytes -= bytes(event.getData());
					abandon(event.getData());
				}
			}
		}
	}

	/** A message the closed stream will not take. */
	private static void abandon(Message message) {
		AcknowledgeSupport ack = AcknowledgeSupport.of(message);
		if (ack != null) {
			ack.abandoned();
		}
	}

	/** @return the messages the buffer takes at most */
	int size() {
		return size;
	}

	/** @return {@code true} if a full buffer drops messages instead of blocking or failing */
	boolean dropsWhenFull() {
		return policy == Policy.DISCARD_OLDEST;
	}

	/** @return the messages in the buffer, not yet taken by the stream */
	synchronized int buffered() {
		return Math.max(0, messages);
	}

	/** Counts and settles a message that does not reach the stream. */
	private void dropped(Message message) {
		synchronized (this) {
			dropped++;
		}
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
	 * @return {@code true} to deliver it; {@code false} if it is dropped, as a message of a group
	 *         that lost messages before it
	 */
	boolean taken(Message message) {
		boolean drop;
		synchronized (this) {
			messages--;
			bytes -= bytes(message);
			notifyAll();
			if (gap && startsGroup(message)) {
				gap = false;
			}
			drop = gap;
		}
		if (drop) {
			dropped(message);
			return false;
		}
		AcknowledgeSupport ack = AcknowledgeSupport.of(message);
		if (ack != null) {
			ack.taken();
		}
		return true;
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
		if (handle.get() == null) {
			// not attached to a subscription of its own: the buffer's drops are all there are
			dto.droppedMessages = dropped;
		}
	}

}
