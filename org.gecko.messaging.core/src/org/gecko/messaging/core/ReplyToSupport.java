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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.MessagingException;
import org.gecko.messaging.api.replyto.ReplyToPublisher;
import org.gecko.messaging.api.runtime.ConnectionState;
import org.osgi.util.promise.Deferred;
import org.osgi.util.promise.Promise;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushEventConsumer;
import org.osgi.util.pushstream.PushEventSource;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamProvider;

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

		void fail(Throwable t);

	}

	private final AbstractMessagingInstance instance;
	private final Map<String, Pending> pending = new ConcurrentHashMap<>();
	private final PushStreamProvider pushStreams = new PushStreamProvider();
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
			Pending p = new Pending() {
				@Override
				public boolean response(Message message) {
					deferred.resolve(message);
					return true;
				}

				@Override
				public void fail(Throwable t) {
					deferred.fail(t);
				}
			};
			SubscriptionHandle own = register(r, p);
			timeout[0] = instance.scheduler().schedule(() -> {
				if (pending.remove(r.correlationId, p)) {
					release(own);
					deferred.fail(new TimeoutException("No response to " + r.correlationId + " within " + r.timeout + " ms"));
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
		Request r = prepare(requestMessage, context);
		PushEventSource<Message> source = consumer -> openMany(r, consumer);
		return pushStreams.buildStream(source).withExecutor(instance.workers()).withScheduler(instance.scheduler()).build();
	}

	private AutoCloseable openMany(Request r, PushEventConsumer<? super Message> consumer) throws Exception {
		Object lock = new Object();
		ScheduledFuture<?>[] timeout = new ScheduledFuture<?>[1];
		boolean[] done = new boolean[1];
		SubscriptionHandle[] own = new SubscriptionHandle[1];
		Pending p = new Pending() {
			@Override
			public boolean response(Message message) {
				synchronized (lock) {
					if (done[0]) {
						return true;
					}
					reschedule();
					try {
						if (Boolean.TRUE.equals(Features.bool(message.getContext().getHeaders().get(HEADER_REPLY_COMPLETE)))) {
							end(PushEvent.close());
							return true;
						}
						if (consumer.accept(PushEvent.data(message)) < 0) {
							end(null);
							return true;
						}
					} catch (Exception e) {
						end(null);
						return true;
					}
					return false;
				}
			}

			@Override
			public void fail(Throwable t) {
				synchronized (lock) {
					end(PushEvent.error(t));
				}
			}

			private void reschedule() {
				if (timeout[0] != null) {
					timeout[0].cancel(false);
				}
				timeout[0] = instance.scheduler().schedule(() -> {
					fail(new TimeoutException("No further response to " + r.correlationId + " within " + r.timeout + " ms"));
				}, r.timeout, TimeUnit.MILLISECONDS);
			}

			private void end(PushEvent<Message> last) {
				if (done[0]) {
					return;
				}
				done[0] = true;
				if (timeout[0] != null) {
					timeout[0].cancel(false);
				}
				pending.remove(r.correlationId, this);
				release(own[0]);
				if (last != null) {
					try {
						consumer.accept(last);
					} catch (Exception e) {
						LOG.log(Level.FINE, "Closing a response stream failed", e);
					}
				}
			}
		};
		synchronized (lock) {
			own[0] = register(r, p);
			timeout[0] = instance.scheduler().schedule(() -> p.fail(new TimeoutException("No response to " + r.correlationId
					+ " within " + r.timeout + " ms")), r.timeout, TimeUnit.MILLISECONDS);
		}
		send(r, own[0], p);
		return () -> {
			synchronized (lock) {
				if (!done[0]) {
					done[0] = true;
					if (timeout[0] != null) {
						timeout[0].cancel(false);
					}
					pending.remove(r.correlationId, p);
					release(own[0]);
				}
			}
		};
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
		Long t = Features.number(ctx.getExtensions().get(EXTENSION_REPLY_TIMEOUT));
		long timeout = t != null && t > 0 ? t : instance.getConfiguration().replyTimeout();
		MessageContext request = instance.newBuilder().withContext(ctx).correlationId(correlationId).replyTo(replyTo)
				.extensionEntry(EXTENSION_REPLY_TIMEOUT, null).buildContext();
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
				return 0;
			}
			if (p.response(message)) {
				pending.remove(id, p);
			}
			return 0;
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

	void close() {
		MessagingException closed = new MessagingException("Messaging instance " + instance.getName() + " is stopped");
		for (Map.Entry<String, Pending> e : pending.entrySet()) {
			if (pending.remove(e.getKey(), e.getValue())) {
				e.getValue().fail(closed);
			}
		}
	}

}
