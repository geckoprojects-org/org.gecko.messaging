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

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.dto.ReplyToSubscriptionDTO;
import org.gecko.messaging.api.replyto.ReplyToManySubscriptionHandler;
import org.gecko.messaging.api.replyto.ReplyToPublisher;
import org.gecko.messaging.api.replyto.ReplyToSingleSubscriptionHandler;
import org.gecko.messaging.api.runtime.MessageServiceRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.util.pushstream.PushStreamProvider;

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
		return builder().channel(channel).correlationId(id).replyTo(tck.channel("wb", id, "reply")).buildContext();
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

}
