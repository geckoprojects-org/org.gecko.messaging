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
import static org.gecko.messaging.api.replyto.ReplyToSubscriptionConstants.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.stream.IntStream;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.MessagingException;
import org.gecko.messaging.api.dto.ReplyToSubscriptionDTO;
import org.gecko.messaging.api.replyto.ReplyToManySubscriptionHandler;
import org.gecko.messaging.api.replyto.ReplyToPublisher;
import org.gecko.messaging.api.replyto.ReplyToSingleSubscriptionHandler;
import org.gecko.messaging.api.runtime.MessageServiceRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.util.promise.Promise;
import org.osgi.util.pushstream.PushStreamProvider;
import org.osgi.util.pushstream.SimplePushEventSource;

/**
 * The responding side of reply-to: handlers registered as whiteboard services (MSG160).
 */
public class ReplyToWhiteboardTest extends AbstractMessagingTest {

	private MessageServiceRuntime runtime;
	private ReplyToPublisher replyTo;

	@BeforeEach
	void needsWhiteboard() {
		assumeTrue(tck.has(FEATURE_REPLY_TO_SUBSCRIPTION) && tck.has(FEATURE_REPLY_TO));
		runtime = tck.service(MessageServiceRuntime.class);
		replyTo = tck.service(ReplyToPublisher.class);
	}

	private Hashtable<String, Object> handlerProperties(String channel) {
		Hashtable<String, Object> props = new Hashtable<>();
		props.put(MESSAGING_TARGET_PROPERTY, tck.filter());
		props.put(REPLY_TO_SUBSCRIPTION_CHANNEL_PROPERTY, channel);
		return props;
	}

	private ReplyToSubscriptionDTO awaitBound(String channel) {
		ReplyToSubscriptionDTO[] found = new ReplyToSubscriptionDTO[1];
		tck.await("handler bound on " + channel, () -> {
			found[0] = Arrays.stream(runtime.getRuntimeDTO().replyToSubscriptions)
					.filter(d -> channel.equals(d.requestChannel.name) && d.requestChannel.connected).findFirst().orElse(null);
			return found[0] != null;
		});
		return found[0];
	}

	private MessageContext request(String channel, String id) {
		// a reply channel an instance answers by default (MSG: replyToAllowed)
		return builder().channel(channel).correlationId(id)
				.replyTo(DEFAULT_REPLY_CHANNEL_PREFIX + tck.channel("wb", id, "reply")).buildContext();
	}

	@Test
	void requestNamingAForeignReplyChannelIsNotAnswered() throws Exception {
		String channel = tck.channel("wb", "foreign");
		String foreign = tck.channel("wb", "foreign", "victim");
		List<String> handled = Collections.synchronizedList(new ArrayList<>());
		register(ReplyToSingleSubscriptionHandler.class, (request, response) -> {
			handled.add(Tck.text(request));
			return response.content(Tck.bytes("answer")).buildMessage();
		}, handlerProperties(channel));
		awaitBound(channel);
		List<Message> atVictim = collect(foreign);
		publisher.publish(builder().content(Tck.bytes("redirect")).buildMessage(),
				builder().channel(channel).correlationId("f-1").replyTo(foreign).buildContext()).getValue();
		tck.await("request refused", () -> awaitBound(channel).refusedRequests == 1);
		tck.settle();
		assertThat(handled).as("not handled at all").isEmpty();
		assertThat(atVictim).as("no response to a channel the sender chose").isEmpty();
	}

	@Test
	void singleHandlerAnswers() throws Exception {
		String channel = tck.channel("wb", "single");
		ReplyToSingleSubscriptionHandler handler = (request, response) -> response
				.content(Tck.bytes(Tck.text(request).toUpperCase())).buildMessage();
		register(ReplyToSingleSubscriptionHandler.class, handler, handlerProperties(channel));
		ReplyToSubscriptionDTO dto = awaitBound(channel);
		assertThat(dto.many).isFalse();
		assertThat(dto.handlerService).isNotNull();
		Message response = replyTo.publishWithReply(builder().content(Tck.bytes("hello")).buildMessage(), request(channel, "s-1"))
				.getValue();
		assertThat(Tck.text(response)).isEqualTo("HELLO");
		assertThat(response.getContext().getCorrelationId()).isEqualTo("s-1");
	}

	@Test
	void manyHandlerStreamsAndCompletes() throws Exception {
		assumeTrue(tck.has(FEATURE_REPLY_TO_MANY));
		String channel = tck.channel("wb", "many");
		PushStreamProvider psp = new PushStreamProvider();
		ReplyToManySubscriptionHandler handler = (request, responses) -> psp
				.streamOf(List.of("1", "2", "3").stream().map(s -> responses.get().content(Tck.bytes(Tck.text(request) + s)).buildMessage()));
		register(ReplyToManySubscriptionHandler.class, handler, handlerProperties(channel));
		assertThat(awaitBound(channel).many).isTrue();
		List<String> got = Collections.synchronizedList(new ArrayList<>());
		replyTo.publishWithReplyMany(builder().content(Tck.bytes("n")).buildMessage(), request(channel, "m-1")).map(Tck::text)
				.forEach(got::add).getValue();
		assertThat(got).containsExactly("n1", "n2", "n3");
	}

	@Test
	void defaultReplyChannelForRequestsWithout() throws Exception {
		String channel = tck.channel("wb", "default");
		String replies = tck.channel("wb", "default", "replies");
		Hashtable<String, Object> props = handlerProperties(channel);
		props.put(REPLY_TO_SUBSCRIPTION_REPLY_CHANNEL_PROPERTY, replies);
		register(ReplyToSingleSubscriptionHandler.class, (request, response) -> response.content(Tck.bytes("d")).buildMessage(), props);
		awaitBound(channel);
		List<Message> received = collect(replies);
		publisher.publish(text(channel, "no reply-to")).getValue();
		tck.awaitSize(received, 1);
		assertThat(Tck.text(received.get(0))).isEqualTo("d");
	}

	@Test
	void handlerWithoutTargetIsNotBound() {
		String channel = tck.channel("wb", "untargeted");
		Hashtable<String, Object> props = new Hashtable<>();
		props.put(REPLY_TO_SUBSCRIPTION_CHANNEL_PROPERTY, channel);
		register(ReplyToSingleSubscriptionHandler.class, (request, response) -> null, props);
		tck.settle();
		assertThat(runtime.getRuntimeDTO().replyToSubscriptions).noneMatch(d -> channel.equals(d.requestChannel.name));
	}

	@Test
	void unregisteringUnbinds() {
		String channel = tck.channel("wb", "gone");
		var registration = bundleContext.registerService(ReplyToSingleSubscriptionHandler.class,
				(request, response) -> response.buildMessage(), handlerProperties(channel));
		awaitBound(channel);
		registration.unregister();
		tck.await("handler unbound", () -> Arrays.stream(runtime.getRuntimeDTO().replyToSubscriptions)
				.noneMatch(d -> channel.equals(d.requestChannel.name)));
	}

	@Test
	void aFailingHandlerFailsTheRequestAtOnce() throws Exception {
		String channel = tck.channel("wb", "failing");
		register(ReplyToSingleSubscriptionHandler.class, (request, response) -> {
			throw new IllegalStateException("internal detail");
		}, handlerProperties(channel));
		awaitBound(channel);
		long start = System.currentTimeMillis();
		Throwable failure = replyTo.publishWithReply(builder().content(Tck.bytes("x")).buildMessage(), request(channel, "e-1"))
				.getFailure();
		assertThat(failure).isInstanceOf(MessagingException.class).hasMessageContaining("responder failed")
				.hasMessageNotContaining("internal detail");
		assertThat(System.currentTimeMillis() - start).as("not by the reply timeout").isLessThan(tck.timeout());
	}

	@Test
	void aSlowConsumerReceivesEveryResponseOfAFastResponder() throws Exception {
		// the responder's stream is synchronous and pushes at once; the consumer is slower: the
		// responder is held back by the send window, the requester by its buffer, nothing fails
		assumeTrue(tck.has(FEATURE_REPLY_TO_MANY));
		int count = 500;
		String channel = tck.channel("wb", "flow");
		PushStreamProvider psp = new PushStreamProvider();
		ReplyToManySubscriptionHandler handler = (request, responses) -> psp.streamOf(
				IntStream.range(0, count).mapToObj(n -> responses.get().content(Tck.bytes(String.valueOf(n))).buildMessage()));
		register(ReplyToManySubscriptionHandler.class, handler, handlerProperties(channel));
		awaitBound(channel);
		List<String> got = Collections.synchronizedList(new ArrayList<>());
		Promise<Void> done = replyTo.publishWithReplyMany(builder().content(Tck.bytes("n")).buildMessage(), request(channel, "flow-1"))
				.map(Tck::text).forEach(t -> {
					if (got.size() % 50 == 0) {
						try {
							Thread.sleep(20);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
					}
					got.add(t);
				});
		assertThat(done.getFailure()).isNull();
		assertThat(got).containsExactlyElementsOf(IntStream.range(0, count).mapToObj(String::valueOf).toList());
		tck.await("response stream closed", () -> runtime.getRuntimeDTO().replyStreams.length == 0);
	}

	@Test
	void aFailingResponseStreamEndsWithAnError() throws Exception {
		assumeTrue(tck.has(FEATURE_REPLY_TO_MANY));
		String channel = tck.channel("wb", "failingMany");
		PushStreamProvider psp = new PushStreamProvider();
		// a stream that delivers one response and then fails, as an event source reports a failure
		ReplyToManySubscriptionHandler handler = (request, responses) -> {
			SimplePushEventSource<Message> source = psp.createSimpleEventSource(Message.class);
			source.connectPromise().onSuccess(v -> {
				source.publish(responses.get().content(Tck.bytes("1")).buildMessage());
				source.error(new IllegalStateException("internal detail"));
			});
			return psp.createStream(source);
		};
		register(ReplyToManySubscriptionHandler.class, handler, handlerProperties(channel));
		awaitBound(channel);
		Throwable failure = replyTo.publishWithReplyMany(builder().content(Tck.bytes("n")).buildMessage(), request(channel, "e-2"))
				.forEach(m -> {
				}).getFailure();
		assertThat(failure).isInstanceOf(MessagingException.class).hasMessageContaining("responder failed");
	}

}
