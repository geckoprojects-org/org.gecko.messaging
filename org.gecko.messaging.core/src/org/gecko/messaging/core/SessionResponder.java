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

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContextBuilder;
import org.gecko.messaging.api.replyto.ReplyToSessionHandler;
import org.osgi.util.pushstream.PushStream;

/**
 * The responding side of one duplex session. It subscribes a channel of its own for the
 * requester's messages (generated, or under the handler's session channel prefix) (control and data in two subscriptions, as {@link SessionClient}), lets
 * the handler open the session, answers {@code accept}, and publishes the handler's stream to the
 * requester once it is {@code ready}.
 */
final class SessionResponder {

	private static final Logger LOG = Logger.getLogger(SessionResponder.class.getName());

	private final AbstractMessagingInstance instance;
	private final String id;
	private final String replyChannel;
	private final String channel;
	private final long lease;
	private final Set<SessionResponder> open;
	private final AtomicBoolean started = new AtomicBoolean();
	private final AtomicBoolean ended = new AtomicBoolean();
	private volatile long lastSeen = System.nanoTime();
	private volatile long lastSent = System.nanoTime();
	private volatile SubscriptionHandle control;
	/** The requester's messages: taken by the control subscription, held until the handler connects. */
	private final HeldSource messages;
	private volatile PushStream<Message> responses;
	private volatile ScheduledFuture<?> timer;

	private SessionResponder(AbstractMessagingInstance instance, String id, String replyChannel, String channelPrefix, long lease,
			Set<SessionResponder> open) {
		this.instance = instance;
		this.id = id;
		this.replyChannel = replyChannel;
		this.channel = channelPrefix != null && !channelPrefix.isEmpty() ? channelPrefix + "/" + UUID.randomUUID()
				: instance.createReplyChannel();
		this.lease = lease;
		this.open = open;
		this.messages = new HeldSource(instance.getConfiguration().bufferSize());
	}

	/**
	 * Opens a session on a request, or refuses it.
	 *
	 * @param instance the instance
	 * @param handler the handler
	 * @param request the {@code open} request, its reply-to channel allowed
	 * @param channelPrefix the prefix of the session's own channel, {@code null} for a generated one
	 * @param open the open sessions of the handler
	 * @param responseBuilders builders of messages to the requester
	 * @param refuse tells the requester a refusal
	 */
	static void open(AbstractMessagingInstance instance, ReplyToSessionHandler handler, Message request, String channelPrefix,
			Set<SessionResponder> open,
			Supplier<MessageContextBuilder> responseBuilders, Runnable refuse) {
		String id = request.getContext().getCorrelationId();
		String reply = request.getContext().getReplyToChannel();
		if (id == null || reply == null || !"open".equals(request.getContext().getHeaders().get(HEADER_SESSION))) {
			return; // not a session request: nothing to answer to
		}
		MessagingConfiguration config = instance.getConfiguration();
		if (open.size() >= config.number("maxSessions", 16)) {
			LOG.fine(() -> "Refusing session " + id + ": too many open sessions");
			refuse.run();
			return;
		}
		long min = config.number("minSessionLease", 1_000);
		long max = config.number("maxSessionLease", 300_000);
		Long asked = Extensions.number(request.getContext().getHeaders().get(HEADER_SESSION_LEASE));
		long lease = Math.min(max, Math.max(min, asked != null ? asked : config.number("sessionLease", 10_000)));
		SessionResponder s = new SessionResponder(instance, id, reply, channelPrefix, lease, open);
		open.add(s);
		try {
			s.start(handler, request, responseBuilders);
		} catch (Exception e) {
			LOG.log(Level.WARNING, "Could not open session " + id, e);
			s.end(e, false);
			refuse.run();
		}
	}

	private void start(ReplyToSessionHandler handler, Message request, Supplier<MessageContextBuilder> responseBuilders) throws Exception {
		control = instance.addSubscription(instance.replySubscriptionContext(channel), new SubscriptionHandle.Sink() {
			@Override
			public long accept(Message message) {
				control(message);
				return 0;
			}

			@Override
			public void closed(Throwable failure) {
				if (failure != null) {
					end(failure, true);
				}
			}
		}, true);
		PushStream<Message> in = instance.stream(messages, new SimpleMessageContextBuilder().channel(channel).buildSimple());
		PushStream<Message> out = handler.handleSession(request, in, responseBuilders);
		if (out == null) {
			throw new IllegalStateException("The session handler returned no stream");
		}
		responses = out;
		long period = Math.max(1, lease / 3);
		timer = instance.scheduler().scheduleWithFixedDelay(this::keep, period, period, TimeUnit.MILLISECONDS);
		lastSent = System.nanoTime();
		instance.publish(instance.newBuilder().buildMessage(), instance.newBuilder().channel(replyChannel).correlationId(id)
				.header(HEADER_SESSION, "accept").header(HEADER_SESSION_CHANNEL, channel).header(HEADER_SESSION_LEASE, lease)
				.buildContext()).onFailure(t -> end(t, false));
	}

	private void control(Message message) {
		if (!id.equals(message.getContext().getCorrelationId())) {
			return;
		}
		lastSeen = System.nanoTime();
		Object kind = message.getContext().getHeaders().get(HEADER_SESSION);
		if (kind == null) {
			messages.offer(message);
		} else if ("ready".equals(kind)) {
			// never on the thread of the protocol: a synchronous source pushes all its messages in
			// the thread that connects it, and would hold up every delivery of the connection
			instance.workers().execute(this::startResponses);
		} else if ("close".equals(kind)) {
			// the requester left: nothing more to tell it
			end(null, false);
		}
	}

	/** Publishes the handler's stream to the requester, then ends the session. */
	private void startResponses() {
		PushStream<Message> out = responses;
		if (out == null || !started.compareAndSet(false, true)) {
			return;
		}
		instance.publish(out.map(m -> {
			lastSent = System.nanoTime();
			return m;
		}), null).onResolve(() -> {
			if (!ended.get()) {
				end(null, true);
			}
		}).onFailure(t -> {
			LOG.log(Level.WARNING, "The stream of session " + id + " failed", t);
			if (!ended.get()) {
				sendControl("close", true);
				end(t, false);
			}
		});
	}

	private void keep() {
		long now = System.nanoTime();
		if (now - lastSeen > TimeUnit.MILLISECONDS.toNanos(lease)) {
			end(new TimeoutException("No message of the requester within the lease of " + lease + " ms"), false);
			return;
		}
		if (now - lastSent >= TimeUnit.MILLISECONDS.toNanos(lease / 3)) {
			sendControl("ping", false);
		}
	}

	private void sendControl(String kind, boolean failed) {
		lastSent = System.nanoTime();
		MessageContextBuilder b = instance.newBuilder().channel(replyChannel).correlationId(id).header(HEADER_SESSION, kind);
		if (failed) {
			b.header(HEADER_REPLY_ERROR, ReplyToWhiteboard.RESPONDER_FAILED);
		}
		instance.publish(instance.newBuilder().buildMessage(), b.buildContext())
				.onFailure(t -> LOG.log(Level.FINE, "Session " + id + ": could not send " + kind, t));
	}

	/**
	 * Ends the session.
	 *
	 * @param failure why, {@code null} for a normal end
	 * @param tell whether to tell the requester {@code close}
	 */
	void end(Throwable failure, boolean tell) {
		if (!ended.compareAndSet(false, true)) {
			return;
		}
		open.remove(this);
		ScheduledFuture<?> t = timer;
		if (t != null) {
			t.cancel(false);
		}
		if (tell) {
			sendControl("close", false);
		}
		messages.end(failure);
		PushStream<Message> out = responses;
		if (out != null) {
			out.close();
		}
		closeLater(control, null);
	}

	private void closeLater(SubscriptionHandle h, Throwable failure) {
		if (h != null) {
			instance.scheduler().execute(() -> instance.closeSubscription(h, failure));
		}
	}

}
