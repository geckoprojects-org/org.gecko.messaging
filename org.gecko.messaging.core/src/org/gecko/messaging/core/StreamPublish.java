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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.MessagingException;
import org.osgi.util.promise.Deferred;
import org.osgi.util.promise.Promise;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushEventConsumer;

/**
 * Publishes the messages of a stream in its order, within the send window of the instance —
 * shared by all its stream publishes and derived from the protocol's own limit, so that the
 * protocol never refuses a message for being one too many: the thread delivering the stream
 * waits for room, never a thread of the protocol. Room is made by the promise callbacks, which run
 * apart from the workers, so that a stream delivered by the workers (a subscription passed on)
 * cannot wait for itself.
 * <p>
 * A failed message ends the publishing ({@code streamPublishFailures=stop}, the default) or is
 * skipped and counted ({@code continue}).
 */
final class StreamPublish implements PushEventConsumer<Message> {

	private final AbstractMessagingInstance instance;
	private final MessageContext context;
	private final boolean skipFailures;
	private final Deferred<Long> result;
	private final AtomicLong handedOver = new AtomicLong();
	private final AtomicInteger outstanding = new AtomicInteger();
	private final AtomicReference<Throwable> failure = new AtomicReference<>();
	private final AtomicBoolean done = new AtomicBoolean();
	private volatile boolean ended;

	/**
	 * @param instance the instance publishing
	 * @param context the context of every message, {@code null} for the context of each message
	 * @param failures {@code stop} or {@code continue}
	 * @param result resolved with the number of messages handed over
	 */
	StreamPublish(AbstractMessagingInstance instance, MessageContext context, String failures, Deferred<Long> result) {
		this.instance = instance;
		this.context = context;
		this.skipFailures = "continue".equals(failures);
		this.result = result;
	}

	/** @return the promise of the whole stream */
	Promise<Long> promise() {
		return result.getPromise();
	}

	@Override
	public long accept(PushEvent<? extends Message> event) throws Exception {
		switch (event.getType()) {
			case DATA:
				return publish(event.getData());
			case ERROR:
				fail(event.getFailure());
				return ABORT;
			default:
				ended = true;
				if (outstanding.get() == 0) {
					finish();
				}
				return ABORT;
		}
	}

	private long publish(Message message) throws InterruptedException {
		if (failure.get() != null) {
			return ABORT;
		}
		if (!instance.acquireSend()) {
			fail(new MessagingException("Messaging instance " + instance.getName() + " is stopped"));
			return ABORT;
		}
		outstanding.incrementAndGet();
		Promise<Void> p = instance.publish(message, context);
		p.onResolve(() -> handedOver(p));
		return failure.get() != null ? ABORT : CONTINUE;
	}

	private void handedOver(Promise<Void> p) {
		instance.releaseSend();
		Throwable f;
		try {
			f = p.getFailure();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			f = e;
		}
		if (f != null && skipFailures) {
			instance.streamPublishFailed();
		} else if (f != null) {
			fail(f);
		} else {
			handedOver.incrementAndGet();
		}
		if (outstanding.decrementAndGet() == 0 && ended) {
			finish();
		}
	}

	private void fail(Throwable f) {
		if (failure.compareAndSet(null, f) && done.compareAndSet(false, true)) {
			result.fail(f);
		}
	}

	private void finish() {
		if (failure.get() == null && done.compareAndSet(false, true)) {
			result.resolve(handedOver.get());
		}
	}

}
