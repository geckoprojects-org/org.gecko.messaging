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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.MessageContextBuilder;
import org.gecko.messaging.api.MessagingException;
import org.gecko.messaging.api.replyto.MessagingSession;
import org.gecko.messaging.api.replyto.SessionEndedException;
import org.gecko.messaging.core.AbstractMessagingInstance.Intercept;
import org.gecko.messaging.core.cloudevents.CloudEventCodec;
import org.osgi.util.promise.Deferred;
import org.osgi.util.promise.Promise;
import org.osgi.util.pushstream.PushStream;

/**
 * The requesting side of a duplex session. It subscribes a reply channel for the messages of the
 * responder and its control messages, opens the session with a request, and then sends to the
 * channel the responder named in its {@code accept}. The reply channel is the one the context
 * names ({@link MessageContext#getReplyToChannel()}), so that it can lie in a tree a broker ACL
 * grants, or else a generated one.
 * <p>
 * Two subscriptions share the reply channel: one for control (accept, ping, close, and the
 * lease, which every message renews), made before the request goes out, and the receive stream,
 * made when the application connects it — whereupon the responder is told {@code ready} and
 * starts sending.
 */
final class SessionClient implements MessagingSession {

	private static final Logger LOG = Logger.getLogger(SessionClient.class.getName());

	/** Extensions of the session context that shape the receive stream. */
	private static final String[] RECEIVE_EXTENSIONS = { EXTENSION_BUFFER_SIZE, EXTENSION_BUFFER_BYTES, EXTENSION_QUEUE_POLICY,
			EXTENSION_GROUP_HEADER, EXTENSION_QOS };

	/** Extensions of the session context that stay with the requester. */
	private static final String[] LOCAL_EXTENSIONS = { EXTENSION_SESSION_LEASE, EXTENSION_REPLY_TIMEOUT, EXTENSION_SESSION_CREDIT };

	private final AbstractMessagingInstance instance;
	private final String id = UUID.randomUUID().toString();
	private final String replyChannel;
	private final Map<String, Object> receiveExtensions;
	/** The messages granted the responder in flight, {@code 0} for no credit. */
	private final int credit;
	/** Messages the application took since the last grant. */
	private final AtomicInteger taken = new AtomicInteger();
	private final Deferred<MessagingSession> opened;
	private final Deferred<Void> closed;
	private final AtomicBoolean ended = new AtomicBoolean();
	private volatile long lease;
	private volatile String sendChannel;
	private volatile long lastSeen = System.nanoTime();
	private volatile long lastSent = System.nanoTime();
	private volatile SubscriptionHandle control;
	private volatile SubscriptionHandle receiving;
	private volatile ScheduledFuture<?> timer;
	private PushStream<Message> receive;

	private SessionClient(AbstractMessagingInstance instance, MessageContext context, long lease) {
		this.instance = instance;
		String named = context.getReplyToChannel();
		this.replyChannel = named != null && !named.isEmpty() ? named : instance.createReplyChannel();
		this.lease = lease;
		this.receiveExtensions = new HashMap<>();
		for (String key : RECEIVE_EXTENSIONS) {
			Object v = context.getExtensions().get(key);
			if (v != null) {
				receiveExtensions.put(key, v);
			}
		}
		this.credit = credit(instance, context, receiveExtensions);
		this.opened = instance.promises().deferred();
		this.closed = instance.promises().deferred();
	}

	/**
	 * The credit: as asked, kept to the receive buffer; by default the buffer when it waits, none
	 * when it drops.
	 */
	private static int credit(AbstractMessagingInstance instance, MessageContext context, Map<String, Object> receiveExtensions) {
		SimpleMessageContextBuilder b = new SimpleMessageContextBuilder();
		receiveExtensions.forEach(b::extensionEntry);
		SubscriptionBuffer buffer = instance.buffer(b.buildSimple());
		Long asked = Extensions.number(context.getExtensions().get(EXTENSION_SESSION_CREDIT));
		if (asked == null) {
			return buffer.dropsWhenFull() ? 0 : buffer.size();
		}
		return (int) Math.max(0, Math.min(asked, buffer.size()));
	}

	/**
	 * Opens a session.
	 *
	 * @param instance the instance
	 * @param request the request, its payload and headers reach the handler
	 * @param context the context with the responder's channel and optionally the reply channel,
	 *            {@code null} for the request's
	 * @return the promise of the session
	 */
	static Promise<MessagingSession> open(AbstractMessagingInstance instance, Message request, MessageContext context) {
		try {
			instance.getFeatures().require(FEATURE_REPLY_TO_SESSION, "A duplex session");
			if (request == null) {
				throw new IllegalArgumentException("openRequest");
			}
			MessageContext ctx = context != null ? context : request.getContext();
			if (ctx == null || ctx.getChannel() == null) {
				throw new MessagingException("A session request needs the channel of the responder");
			}
			Long asked = Extensions.number(ctx.getExtensions().get(EXTENSION_SESSION_LEASE));
			long lease = asked != null && asked > 0 ? asked : instance.getConfiguration().number("sessionLease", 10_000);
			SessionClient s = new SessionClient(instance, ctx, lease);
			s.start(request, ctx);
			return s.opened.getPromise();
		} catch (Exception e) {
			return instance.promises().failed(e);
		}
	}

	private void start(Message request, MessageContext ctx) throws Exception {
		control = instance.addSubscription(instance.replySubscriptionContext(replyChannel), new SubscriptionHandle.Sink() {
			@Override
			public long accept(Message message) {
				control(message);
				return 0;
			}

			@Override
			public void closed(Throwable failure) {
				if (failure != null) {
					end(failure);
				}
			}
		}, true);
		MessageContextBuilder b = instance.newBuilder().withContext(ctx).correlationId(id).replyTo(replyChannel)
				.header(HEADER_SESSION, "open").header(HEADER_SESSION_LEASE, lease);
		for (String key : RECEIVE_EXTENSIONS) {
			b.extensionEntry(key, null);
		}
		for (String key : LOCAL_EXTENSIONS) {
			b.extensionEntry(key, null);
		}
		Long asked = Extensions.number(ctx.getExtensions().get(EXTENSION_REPLY_TIMEOUT));
		long timeout = asked != null && asked > 0 ? asked : instance.getConfiguration().replyTimeout();
		timer = instance.scheduler().schedule(
				() -> end(new TimeoutException("The session " + id + " was not accepted within " + timeout + " ms")), timeout,
				TimeUnit.MILLISECONDS);
		instance.publish(instance.newBuilder().content(request.payload()).buildMessage(), b.buildContext()).onFailure(this::end);
	}

	/** Every message on the channel renews the lease; control messages steer the session. */
	private void control(Message message) {
		if (!id.equals(message.getContext().getCorrelationId())) {
			return;
		}
		lastSeen = System.nanoTime();
		MessagingException failure = ReplyToSupport.responderFailure(message);
		if (failure != null) {
			end(failure);
			return;
		}
		Object kind = message.getContext().getHeaders().get(HEADER_SESSION);
		if ("accept".equals(kind)) {
			Object channel = message.getContext().getHeaders().get(HEADER_SESSION_CHANNEL);
			Long granted = Extensions.number(message.getContext().getHeaders().get(HEADER_SESSION_LEASE));
			if (channel == null || sendChannel != null) {
				return;
			}
			if (granted != null && granted > 0) {
				lease = granted;
			}
			sendChannel = channel.toString();
			ScheduledFuture<?> t = timer;
			if (t != null) {
				t.cancel(false);
			}
			long period = Math.max(1, lease / 3);
			timer = instance.scheduler().scheduleWithFixedDelay(this::keep, period, period, TimeUnit.MILLISECONDS);
			try {
				opened.resolve(this);
			} catch (IllegalStateException e) {
				// ended meanwhile
			}
		} else if ("close".equals(kind)) {
			end(null);
		}
	}

	/** Pings when silent, ends the session when the responder is. */
	private void keep() {
		long now = System.nanoTime();
		if (now - lastSeen > TimeUnit.MILLISECONDS.toNanos(lease)) {
			end(new TimeoutException("No message of the responder within the lease of " + lease + " ms"));
			return;
		}
		if (now - lastSent >= TimeUnit.MILLISECONDS.toNanos(lease / 3)) {
			sendControl("ping");
		}
	}

	@Override
	public String getSessionId() {
		return id;
	}

	@Override
	public synchronized PushStream<Message> receive() {
		if (receive == null) {
			SimpleMessageContextBuilder b = new SimpleMessageContextBuilder().channel(replyChannel);
			receiveExtensions.forEach(b::extensionEntry);
			receive = instance.subscribe(b.buildSimple(), new Intercept() {
				@Override
				public boolean deliver(Message message, SubscriptionHandle h) {
					// the session's own messages, without control
					return id.equals(message.getContext().getCorrelationId())
							&& message.getContext().getHeaders().get(HEADER_SESSION) == null
							&& message.getContext().getHeaders().get(HEADER_REPLY_ERROR) == null;
				}

				@Override
				public Throwable endsAfter(Message message) {
					Object reason = message.getContext().getHeaders().get(HEADER_SESSION_REASON);
					if (reason == null || !id.equals(message.getContext().getCorrelationId())) {
						return null;
					}
					// the reason is remote input: short and on one line
					SessionEndedException failure = new SessionEndedException(CloudEventCodec.printable(reason), message);
					if (!ended.get()) {
						// the responder ended already; tell it anyway, should it still listen
						sendControl("close");
					}
					end(failure);
					return failure;
				}
			}, h -> {
				receiving = h;
				if (ended.get()) {
					closeLater(h, null);
				} else {
					sendControl("ready", credit > 0 ? credit : null);
				}
			});
			if (credit > 0) {
				// taken by the application: grant as much again, in batches of half the credit
				receive = receive.map(m -> {
					took();
					return m;
				});
			}
		}
		return receive;
	}

	private void took() {
		if (taken.incrementAndGet() >= Math.max(1, credit / 2)) {
			int n = taken.getAndSet(0);
			if (n > 0 && !ended.get()) {
				sendControl("credit", n);
			}
		}
	}

	@Override
	public Promise<Void> send(Message message) {
		String channel = sendChannel;
		if (channel == null || ended.get()) {
			return instance.promises().failed(new MessagingException("The session " + id + " is not open"));
		}
		lastSent = System.nanoTime();
		return instance.publish(message, toResponder(message.getContext(), channel));
	}

	@Override
	public Promise<Long> send(PushStream<? extends Message> messages) {
		String channel = sendChannel;
		if (channel == null || ended.get()) {
			messages.close();
			return instance.promises().failed(new MessagingException("The session " + id + " is not open"));
		}
		return instance.publish(messages.map(m -> {
			lastSent = System.nanoTime();
			return instance.newBuilder().withContext(toResponder(m.getContext(), channel)).content(m.payload()).buildMessage();
		}), null);
	}

	private MessageContext toResponder(MessageContext context, String channel) {
		MessageContextBuilder b = instance.newBuilder();
		if (context != null) {
			b.withContext(context);
		}
		return b.channel(channel).correlationId(id).replyTo(null).buildContext();
	}

	private void sendControl(String kind) {
		sendControl(kind, null);
	}

	private void sendControl(String kind, Integer credit) {
		String channel = sendChannel;
		if (channel == null) {
			return;
		}
		lastSent = System.nanoTime();
		MessageContextBuilder b = instance.newBuilder().channel(channel).correlationId(id).header(HEADER_SESSION, kind);
		if (credit != null) {
			b.header(HEADER_SESSION_CREDIT, credit);
		}
		instance.publish(instance.newBuilder().buildMessage(), b.buildContext())
				.onFailure(t -> LOG.log(Level.FINE, "Session " + id + ": could not send " + kind, t));
	}

	@Override
	public Promise<Void> closed() {
		return closed.getPromise();
	}

	@Override
	public void close() {
		if (!ended.get()) {
			sendControl("close");
		}
		end(null);
	}

	private void end(Throwable failure) {
		if (!ended.compareAndSet(false, true)) {
			return;
		}
		ScheduledFuture<?> t = timer;
		if (t != null) {
			t.cancel(false);
		}
		closeLater(control, null);
		closeLater(receiving, failure);
		if (failure == null) {
			closed.resolve(null);
			settle(opened, new MessagingException("The session " + id + " ended before it was accepted"));
		} else {
			closed.fail(failure);
			settle(opened, failure);
		}
	}

	/** Fails a promise that may be resolved already. */
	private static void settle(Deferred<?> deferred, Throwable failure) {
		if (!deferred.getPromise().isDone()) {
			try {
				deferred.fail(failure);
			} catch (IllegalStateException e) {
				// resolved meanwhile
			}
		}
	}

	/**
	 * Ends a subscription from another thread: it may be called on a thread of the protocol,
	 * which some clients do not allow to unsubscribe.
	 */
	private void closeLater(SubscriptionHandle h, Throwable failure) {
		if (h != null) {
			instance.scheduler().execute(() -> instance.closeSubscription(h, failure));
		}
	}

}
