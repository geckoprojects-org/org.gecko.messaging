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

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.MessagingException;
import org.gecko.messaging.api.dto.SubscriptionDTO;
import org.gecko.messaging.api.replyto.MessagingSession;
import org.gecko.messaging.api.replyto.ReplyToPublisher;
import org.gecko.messaging.api.runtime.ConnectionState;
import org.gecko.messaging.core.cloudevents.CloudEventCodec;
import org.osgi.util.promise.Deferred;
import org.osgi.util.promise.Promise;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushEventConsumer;
import org.osgi.util.pushstream.PushEventSource;
import org.osgi.util.pushstream.PushStream;

/**
 * The requesting side of reply-to messaging. Requests without reply-to channel share one generated
 * reply channel per instance, subscribed once and dispatched by correlation id; requests with an
 * own reply-to channel get a subscription of their own for the duration of the exchange.
 */
final class ReplyToSupport implements ReplyToPublisher {

	private static final Logger LOG = Logger.getLogger(ReplyToSupport.class.getName());

	/** An exchange waiting for responses. */
	private interface Pending {

		/** @return {@code true} if the exchange is over */
		boolean response(Message message);

		/**
		 * @return {@code true} if the exchange takes its responses into a buffer and reports when
		 *         they are taken from it; otherwise a response counts as taken once handled
		 */
		default boolean buffers() {
			return false;
		}

		void fail(Throwable t);

	}

	private final AbstractMessagingInstance instance;
	private final Map<String, Pending> pending = new ConcurrentHashMap<>();
	/** The response streams not closed yet, also after their exchange ended. */
	private final Set<Many> streams = ConcurrentHashMap.newKeySet();
	private final Object sharedLock = new Object();
	private String sharedChannel;
	private SubscriptionHandle sharedHandle;

	ReplyToSupport(AbstractMessagingInstance instance) {
		this.instance = instance;
	}

	@Override
	public Promise<Message> publishWithReply(Message requestMessage) {
		return publishWithReply(requestMessage, null);
	}

	@Override
	public Promise<Message> publishWithReply(Message requestMessage, MessageContext context) {
		try {
			instance.getFeatures().require(FEATURE_REPLY_TO, "Reply-to publishing");
			Request r = prepare(requestMessage, context);
			Deferred<Message> deferred = instance.promises().deferred();
			ScheduledFuture<?>[] timeout = new ScheduledFuture<?>[1];
			// The dispatcher resolves before it removes the exchange from pending, so a timeout,
			// close() or a duplicate response can race it; only the first outcome counts.
			AtomicBoolean settled = new AtomicBoolean();
			Pending p = new Pending() {
				@Override
				public boolean response(Message message) {
					if (settled.compareAndSet(false, true)) {
						MessagingException failure = responderFailure(message);
						if (failure != null) {
							deferred.fail(failure);
						} else {
							deferred.resolve(message);
						}
					}
					return true;
				}

				@Override
				public void fail(Throwable t) {
					if (settled.compareAndSet(false, true)) {
						deferred.fail(t);
					}
				}
			};
			SubscriptionHandle own = register(r, p);
			timeout[0] = instance.scheduler().schedule(() -> {
				if (pending.remove(r.correlationId, p)) {
					release(own);
					p.fail(new TimeoutException("No response to " + r.correlationId + " within " + r.timeout + " ms"));
				}
			}, r.timeout, TimeUnit.MILLISECONDS);
			send(r, own, p);
			return deferred.getPromise().onResolve(() -> {
				timeout[0].cancel(false);
				pending.remove(r.correlationId, p);
				release(own);
			});
		} catch (Exception e) {
			return instance.promises().failed(e);
		}
	}

	@Override
	public PushStream<Message> publishWithReplyMany(Message requestMessage) {
		return publishWithReplyMany(requestMessage, null);
	}

	@Override
	public PushStream<Message> publishWithReplyMany(Message requestMessage, MessageContext context) {
		instance.getFeatures().require(FEATURE_REPLY_TO_MANY, "Reply-to publishing with many responses");
		if (requestMessage == null) {
			throw new IllegalArgumentException("requestMessage");
		}
		// buffered like a subscription, with the settings of the request's extensions
		SubscriptionBuffer buffer = instance.buffer(context != null ? context : requestMessage.getContext());
		Request r = prepare(requestMessage, context);
		PushEventSource<Message> source = consumer -> openMany(r, buffer, consumer);
		return instance.stream(source, buffer);
	}

	@Override
	public Promise<MessagingSession> openSession(Message openRequest) {
		return openSession(openRequest, null);
	}

	@Override
	public Promise<MessagingSession> openSession(Message openRequest, MessageContext context) {
		return SessionClient.open(instance, openRequest, context);
	}

	private AutoCloseable openMany(Request r, SubscriptionBuffer buffer, PushEventConsumer<? super Message> consumer)
			throws Exception {
		Many many = new Many(r, buffer, consumer);
		many.open();
		send(r, many.own, many);
		return many::close;
	}

	/**
	 * A request with many responses. The responses go into a buffer of the request's own, so that
	 * a slow consumer holds up neither the dispatcher of a shared reply channel nor other requests;
	 * a response counts as taken, and is acknowledged on the broker, once the stream takes it from
	 * the buffer. The reply timeout runs only while the consumer is not behind.
	 */
	private final class Many implements Pending {

		private final Request r;
		private final SubscriptionBuffer buffer;
		private final PushEventConsumer<? super Message> consumer;
		/** Orders the events into the stream. Held while a full buffer blocks a delivery. */
		private final Object lock = new Object();
		private final AtomicLong received = new AtomicLong();
		private volatile long lastResponse = System.nanoTime();
		private volatile ScheduledFuture<?> timeout;
		/** Written under lock. */
		private volatile boolean done;
		private SubscriptionHandle own;

		Many(Request r, SubscriptionBuffer buffer, PushEventConsumer<? super Message> consumer) {
			this.r = r;
			this.buffer = buffer;
			this.consumer = consumer;
		}

		void open() throws Exception {
			synchronized (lock) {
				own = register(r, this);
				watch(TimeUnit.MILLISECONDS.toNanos(r.timeout));
			}
			streams.add(this);
		}

		@Override
		public boolean buffers() {
			return true;
		}

		@Override
		public boolean response(Message message) {
			lastResponse = System.nanoTime();
			synchronized (lock) {
				if (done) {
					taken(message);
					return true;
				}
				MessagingException failure = responderFailure(message);
				if (failure != null) {
					taken(message);
					end(PushEvent.error(failure));
					return true;
				}
				if (Boolean.TRUE.equals(Extensions.bool(message.getContext().getHeaders().get(HEADER_REPLY_COMPLETE)))) {
					taken(message);
					end(PushEvent.close());
					return true;
				}
				received.incrementAndGet();
				long back;
				try {
					// blocks while the buffer is full, if its policy says so
					back = consumer.accept(PushEvent.data(message));
				} catch (Exception e) {
					back = -1;
				}
				if (back < 0) {
					// the stream did not take it: closed, or failed on a full buffer
					abandon(message);
					end(null);
					return true;
				}
				return false;
			}
		}

		@Override
		public void fail(Throwable t) {
			// first: a delivery blocked on the full buffer returns and frees the lock; what is
			// buffered is given up, the stream ends with the failure
			buffer.release();
			synchronized (lock) {
				end(PushEvent.error(t));
			}
		}

		/** The consumer closed the stream. */
		void close() {
			// first: a delivery blocked on the full buffer returns and frees the lock
			buffer.release();
			synchronized (lock) {
				end(null);
			}
			streams.remove(this);
		}

		private void watch(long nanos) {
			timeout = instance.scheduler().schedule(this::checkTimeout, nanos, TimeUnit.NANOSECONDS);
		}

		/** Without the lock: a delivery blocked on the full buffer holds it. */
		private void checkTimeout() {
			if (done) {
				return;
			}
			long limit = TimeUnit.MILLISECONDS.toNanos(r.timeout);
			if (buffer.buffered() > 0) {
				// the consumer is behind: the responder waits for it, not the other way round
				lastResponse = System.nanoTime();
				watch(limit);
				return;
			}
			long idle = System.nanoTime() - lastResponse;
			if (idle < limit) {
				watch(limit - idle);
				return;
			}
			fail(new TimeoutException((received.get() == 0 ? "No response to " : "No further response to ") + r.correlationId
					+ " within " + r.timeout + " ms"));
		}

		/** Under lock. */
		private void end(PushEvent<Message> last) {
			if (done) {
				return;
			}
			done = true;
			ScheduledFuture<?> t = timeout;
			if (t != null) {
				t.cancel(false);
			}
			pending.remove(r.correlationId, this);
			release(own);
			if (last != null) {
				try {
					consumer.accept(last);
				} catch (Exception e) {
					LOG.log(Level.FINE, "Closing a response stream failed", e);
				}
			}
		}

		SubscriptionDTO toDTO() {
			SubscriptionDTO dto = new SubscriptionDTO();
			dto.channel = SubscriptionHandle.channelDTO(r.replyTo, null, instance.getConnectionState() == ConnectionState.CONNECTED);
			dto.receivedMessages = received.get();
			buffer.fill(dto);
			return dto;
		}

	}

	/** A response is taken as soon as it is handled: acknowledged on the broker, if it has to be. */
	private static void taken(Message message) {
		AcknowledgeSupport ack = AcknowledgeSupport.of(message);
		if (ack != null) {
			ack.taken();
		}
	}

	/** A response the stream will not take. */
	private static void abandon(Message message) {
		AcknowledgeSupport ack = AcknowledgeSupport.of(message);
		if (ack != null) {
			ack.abandoned();
		}
	}

	/** @return the buffers of the response streams open now */
	SubscriptionDTO[] streamDTOs() {
		return streams.stream().map(Many::toDTO).toArray(SubscriptionDTO[]::new);
	}

	private record Request(Message message, MessageContext context, String correlationId, String replyTo, boolean shared,
			long timeout) {
	}

	private Request prepare(Message requestMessage, MessageContext context) {
		if (requestMessage == null) {
			throw new IllegalArgumentException("requestMessage");
		}
		MessageContext ctx = context != null ? context : requestMessage.getContext();
		if (ctx == null || ctx.getChannel() == null) {
			throw new MessagingException("A reply-to request needs a channel");
		}
		String correlationId = ctx.getCorrelationId();
		if (correlationId == null) {
			instance.getFeatures().require(FEATURE_GENERATE_CORRELATION_ID, "A request without correlation id");
			correlationId = UUID.randomUUID().toString();
		}
		String replyTo = ctx.getReplyToChannel();
		boolean shared = false;
		if (replyTo == null) {
			instance.getFeatures().require(FEATURE_GENERATE_REPLY_CHANNEL, "A request without reply-to channel");
			replyTo = sharedChannel();
			shared = true;
		}
		Long t = Extensions.number(ctx.getExtensions().get(EXTENSION_REPLY_TIMEOUT));
		long timeout = t != null && t > 0 ? t : instance.getConfiguration().replyTimeout();
		// settings of the requesting side, not to be sent
		MessageContext request = instance.newBuilder().withContext(ctx).correlationId(correlationId).replyTo(replyTo)
				.extensionEntry(EXTENSION_REPLY_TIMEOUT, null).extensionEntry(EXTENSION_BUFFER_SIZE, null)
				.extensionEntry(EXTENSION_BUFFER_BYTES, null).extensionEntry(EXTENSION_QUEUE_POLICY, null)
				.extensionEntry(EXTENSION_GROUP_HEADER, null).buildContext();
		return new Request(requestMessage, request, correlationId, replyTo, shared, timeout);
	}

	private String sharedChannel() {
		synchronized (sharedLock) {
			if (sharedChannel == null) {
				sharedChannel = instance.createReplyChannel();
			}
			return sharedChannel;
		}
	}

	/**
	 * Registers the exchange and makes sure its reply channel is subscribed — before the request
	 * goes out, or a fast responder could answer into the void.
	 */
	private SubscriptionHandle register(Request r, Pending p) throws Exception {
		if (instance.getConnectionState() != ConnectionState.CONNECTED) {
			throw new MessagingException("Messaging instance " + instance.getName() + " is not connected");
		}
		if (pending.putIfAbsent(r.correlationId, p) != null) {
			throw new MessagingException("A request with correlation id " + r.correlationId + " is already waiting");
		}
		try {
			if (r.shared) {
				synchronized (sharedLock) {
					if (sharedHandle == null || sharedHandle.isClosed()) {
						sharedHandle = instance.addSubscription(instance.replySubscriptionContext(r.replyTo), new Dispatcher(null), true);
					}
				}
				return null;
			}
			return instance.addSubscription(instance.replySubscriptionContext(r.replyTo), new Dispatcher(r.correlationId), true);
		} catch (Exception e) {
			pending.remove(r.correlationId, p);
			throw e;
		}
	}

	private void send(Request r, SubscriptionHandle own, Pending p) {
		instance.publish(r.message, r.context).onFailure(t -> {
			if (pending.remove(r.correlationId, p)) {
				release(own);
				p.fail(t);
			}
		});
	}

	private void release(SubscriptionHandle own) {
		if (own != null) {
			instance.closeSubscription(own, null);
		}
	}

	/** Hands responses to the waiting exchange of their correlation id. */
	private final class Dispatcher implements SubscriptionHandle.Sink {

		/** The only exchange of an own reply channel, {@code null} for the shared one. */
		private final String only;

		Dispatcher(String only) {
			this.only = only;
		}

		@Override
		public long accept(Message message) {
			String id = message.getContext().getCorrelationId();
			Pending p = id == null ? null : pending.get(id);
			if (p == null) {
				LOG.fine(() -> "Dropping a response without waiting request: " + id);
				taken(message);
				return 0;
			}
			boolean buffers = p.buffers();
			if (p.response(message)) {
				pending.remove(id, p);
			}
			if (!buffers) {
				taken(message);
			}
			return 0;
		}

		@Override
		public boolean takesItself() {
			return true;
		}

		@Override
		public void closed(Throwable failure) {
			if (failure == null) {
				return;
			}
			if (only != null) {
				Pending p = pending.remove(only);
				if (p != null) {
					p.fail(failure);
				}
				return;
			}
			for (Map.Entry<String, Pending> e : pending.entrySet()) {
				if (pending.remove(e.getKey(), e.getValue())) {
					e.getValue().fail(failure);
				}
			}
		}

	}

	/** @return the failure a response with {@code replyerror} reports, {@code null} for others */
	static MessagingException responderFailure(Message response) {
		Object reason = response.getContext().getHeaders().get(HEADER_REPLY_ERROR);
		// the reason is remote input: short and on one line in the message
		return reason == null ? null : new MessagingException("The responder failed: " + CloudEventCodec.printable(reason));
	}

	void close() {
		MessagingException closed = new MessagingException("Messaging instance " + instance.getName() + " is stopped");
		for (Map.Entry<String, Pending> e : pending.entrySet()) {
			if (pending.remove(e.getKey(), e.getValue())) {
				e.getValue().fail(closed);
			}
		}
	}

}
