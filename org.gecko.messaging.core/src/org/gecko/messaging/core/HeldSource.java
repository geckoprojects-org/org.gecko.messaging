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

import java.util.ArrayDeque;
import java.util.Deque;

import org.gecko.messaging.api.Message;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushEventConsumer;
import org.osgi.util.pushstream.PushEventSource;

/**
 * A source that holds what arrives before its stream is connected, at most a limit (the oldest
 * goes first), and hands it over on connect. One consumer.
 */
final class HeldSource implements PushEventSource<Message> {

	private final int limit;
	private final Deque<PushEvent<Message>> held = new ArrayDeque<>();
	private PushEventConsumer<? super Message> consumer;
	private boolean ended;
	private long dropped;

	/** @param limit the messages held at most before the stream is connected */
	HeldSource(int limit) {
		this.limit = Math.max(1, limit);
	}

	/**
	 * Passes on a message, or holds it.
	 *
	 * @param message the message
	 */
	void offer(Message message) {
		push(PushEvent.data(message));
	}

	/**
	 * Ends the stream, after what is held.
	 *
	 * @param failure the cause, {@code null} for a normal end
	 */
	void end(Throwable failure) {
		push(failure == null ? PushEvent.close() : PushEvent.error(failure));
	}

	private void push(PushEvent<Message> event) {
		PushEventConsumer<? super Message> c;
		synchronized (this) {
			if (ended) {
				return;
			}
			ended = event.isTerminal();
			c = consumer;
			if (c == null) {
				if (!event.isTerminal() && held.size() >= limit) {
					held.pollFirst();
					dropped++;
				}
				held.addLast(event);
				return;
			}
		}
		deliver(c, event);
	}

	private void deliver(PushEventConsumer<? super Message> c, PushEvent<Message> event) {
		try {
			if (c.accept(event) < 0 && !event.isTerminal()) {
				synchronized (this) {
					ended = true;
					consumer = null;
				}
			}
		} catch (Exception e) {
			synchronized (this) {
				ended = true;
				consumer = null;
			}
		}
	}

	@Override
	public AutoCloseable open(PushEventConsumer<? super Message> aConsumer) {
		synchronized (this) {
			if (consumer != null) {
				throw new IllegalStateException("The stream is connected already");
			}
			// handed over in order before anything new: new events wait for this lock
			while (!held.isEmpty()) {
				deliver(aConsumer, held.pollFirst());
			}
			consumer = aConsumer;
		}
		return () -> {
			synchronized (this) {
				consumer = null;
				ended = true;
			}
		};
	}

	/** @return the messages dropped while not connected */
	synchronized long dropped() {
		return dropped;
	}

}
