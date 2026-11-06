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
package org.gecko.messaging.tck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.gecko.messaging.api.MessagingConstants.*;
import static org.gecko.messaging.api.replyto.ReplyToSubscriptionConstants.REPLY_TO_SUBSCRIPTION_CHANNEL_PROPERTY;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.dto.ReplyToSubscriptionDTO;
import org.gecko.messaging.api.replyto.MessagingSession;
import org.gecko.messaging.api.replyto.ReplyToPublisher;
import org.gecko.messaging.api.replyto.ReplyToSessionHandler;
import org.gecko.messaging.api.runtime.MessageServiceRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.util.promise.Promise;
import org.osgi.util.pushstream.PushStreamProvider;
import org.osgi.util.pushstream.SimplePushEventSource;

/**
 * Duplex sessions (#19): a stream from the responder and messages to it, for as long as the
 * session lives — the case of a video stream steered by commands.
 */
public class SessionTest extends AbstractMessagingTest {

	private ReplyToPublisher replyTo;
	private final List<MessagingSession> sessions = new ArrayList<>();
	private final PushStreamProvider streams = new PushStreamProvider();

	@BeforeEach
	void needsSessions() {
		assumeTrue(tck.has(FEATURE_REPLY_TO_SESSION));
		replyTo = tck.service(ReplyToPublisher.class);
	}

	@AfterEach
	void closeSessions() {
		sessions.forEach(MessagingSession::close);
	}

	private void handler(String channel, ReplyToSessionHandler handler) {
		Hashtable<String, Object> props = new Hashtable<>();
		props.put(MESSAGING_TARGET_PROPERTY, tck.filter());
		props.put(REPLY_TO_SUBSCRIPTION_CHANNEL_PROPERTY, channel);
		register(ReplyToSessionHandler.class, handler, props);
		tck.settle();
	}

	private MessagingSession open(String channel) throws Exception {
		MessagingSession s = replyTo.openSession(text(channel, "open")).getValue();
		sessions.add(s);
		return s;
	}

	@Test
	void aSessionCarriesBothDirections() throws Exception {
		String channel = tck.channel("session", "echo");
		// every command is answered: the commands stream is connected when the answers start
		handler(channel, (request, commands, builders) -> commands
				.map(c -> builders.get().content(Tck.bytes("echo " + Tck.text(c))).buildMessage()));
		MessagingSession s = open(channel);
		List<Message> received = new CopyOnWriteArrayList<>();
		track(s.receive()).forEach(received::add);
		// sent at once after the open: held until the handler's stream is connected
		for (int i = 0; i < 5; i++) {
			s.send(builder().content(Tck.bytes("c" + i)).buildMessage()).getValue();
		}
		tck.awaitSize(received, 5);
		assertThat(received).extracting(Tck::text).containsExactly("echo c0", "echo c1", "echo c2", "echo c3", "echo c4");
		assertThat(received.get(0).getContext().getCorrelationId()).isEqualTo(s.getSessionId());
	}

	@Test
	void theEndOfTheRespondersStreamEndsTheSessionAfterItsMessages() throws Exception {
		String channel = tck.channel("session", "frames");
		handler(channel, (request, commands, builders) -> streams.streamOf(
				Stream.of("f0", "f1", "f2").map(f -> builders.get().content(Tck.bytes(f)).buildMessage())));
		MessagingSession s = open(channel);
		List<Message> received = new CopyOnWriteArrayList<>();
		CountDownLatch ended = new CountDownLatch(1);
		track(s.receive()).onClose(ended::countDown).forEach(received::add);
		assertThat(ended.await(tck.timeout(), TimeUnit.MILLISECONDS)).as("the receive stream ends").isTrue();
		assertThat(received).extracting(Tck::text).containsExactly("f0", "f1", "f2");
		s.closed().getValue();
	}

	@Test
	void closingTheSessionEndsTheHandlersStreams() throws Exception {
		String channel = tck.channel("session", "close");
		CountDownLatch commandsEnded = new CountDownLatch(1);
		SimplePushEventSource<Message> frames = streams.createSimpleEventSource(Message.class);
		handler(channel, (request, commands, builders) -> {
			commands.onClose(commandsEnded::countDown).forEach(c -> {
				// commands are consumed apart from the frames
			});
			return streams.createStream(frames);
		});
		MessagingSession s = open(channel);
		track(s.receive()).forEach(m -> {
			// frames
		});
		s.close();
		assertThat(commandsEnded.await(tck.timeout(), TimeUnit.MILLISECONDS)).as("the requester's stream ends at the handler").isTrue();
		assertThat(s.closed().getFailure()).isNull();
		tck.await("no open session", () -> openSessions(channel) == 0);
		frames.close();
	}

	@Test
	void aRequesterThatPingsKeepsItsSessionBeyondTheLease() throws Exception {
		String channel = tck.channel("session", "lease");
		handler(channel, (request, commands, builders) -> {
			commands.forEach(c -> {
				// nothing
			});
			return streams.createStream(streams.createSimpleEventSource(Message.class));
		});
		// a short lease; the session object is dropped without close, as by a crashed requester
		MessagingSession s = replyTo.openSession(text(channel, "open"),
				builder().channel(channel).extensionEntry(EXTENSION_SESSION_LEASE, 1000L).buildContext()).getValue();
		assertThat(openSessions(channel)).isEqualTo(1);
		// a live requester pings: the session stays beyond its lease
		Thread.sleep(2500);
		assertThat(openSessions(channel)).as("pinged, still open").isEqualTo(1);
		s.close();
		tck.await("closed", () -> openSessions(channel) == 0);
	}

	@Test
	void aRequesterThatGoesSilentIsDroppedAfterItsLease() throws Exception {
		String channel = tck.channel("session", "silent");
		CountDownLatch commandsEnded = new CountDownLatch(1);
		handler(channel, (request, commands, builders) -> {
			commands.onClose(commandsEnded::countDown).forEach(c -> {
				// nothing
			});
			return streams.createStream(streams.createSimpleEventSource(Message.class));
		});
		// a requester that opens and is never heard of again: no pings, no close
		String reply = DEFAULT_REPLY_CHANNEL_PREFIX + "tck/" + UUID.randomUUID();
		publisher.publish(builder().content(Tck.bytes("open")).buildMessage(), builder().channel(channel).correlationId("silent-1")
				.replyTo(reply).header(HEADER_SESSION, "open").header(HEADER_SESSION_LEASE, 1000L).buildContext()).getValue();
		tck.await("the session opened", () -> openSessions(channel) == 1);
		assertThat(commandsEnded.await(tck.timeout(), TimeUnit.MILLISECONDS)).as("ended after the lease").isTrue();
		tck.await("no open session", () -> openSessions(channel) == 0);
	}

	@Test
	void sessionsBeyondTheLimitAreRefused() throws Exception {
		String channel = tck.channel("session", "limit");
		handler(channel, (request, commands, builders) -> {
			commands.forEach(c -> {
				// nothing
			});
			return streams.createStream(streams.createSimpleEventSource(Message.class));
		});
		Throwable refused = null;
		for (int i = 0; i < 64 && refused == null; i++) {
			Promise<MessagingSession> p = replyTo.openSession(text(channel, "open"));
			refused = p.getFailure();
			if (refused == null) {
				sessions.add(p.getValue());
			}
		}
		assertThat(refused).as("refused beyond maxSessions").isNotNull();
	}

	@Test
	void aSessionWithoutResponderFails() throws Exception {
		String channel = tck.channel("session", "nobody");
		Promise<MessagingSession> p = replyTo.openSession(text(channel, "open"),
				builder().channel(channel).extensionEntry(EXTENSION_REPLY_TIMEOUT, 500L).buildContext());
		assertThat(p.getFailure()).isNotNull();
	}

	private int openSessions(String channel) {
		for (ReplyToSubscriptionDTO dto : tck.service(MessageServiceRuntime.class).getRuntimeDTO().replyToSubscriptions) {
			if (dto.session && channel.equals(dto.requestChannel.name)) {
				return dto.openSessions;
			}
		}
		return -1;
	}

}
