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
import static org.gecko.messaging.api.MessagingConstants.FEATURE_PUBLISH_SUBSCRIBE;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.ByteBuffer;
import java.util.List;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContext;
import org.junit.jupiter.api.Test;
import org.osgi.util.pushstream.PushStream;

/**
 * Sending to and receiving from channels (MSG100, MSG130, MSG200, MSG260, MSG070).
 */
public class PublishSubscribeTest extends AbstractMessagingTest {

	@Test
	void roundTrip() throws Exception {
		String channel = tck.channel("roundtrip");
		List<Message> received = collect(channel);
		publisher.publish(text(channel, "hello")).getValue();
		tck.awaitSize(received, 1);
		assertThat(Tck.text(received.get(0))).isEqualTo("hello");
		assertThat(received.get(0).getContext().getChannel()).as("the channel the message was received on (MSG260)").isEqualTo(channel);
	}

	@Test
	void allByteValuesSurvive() throws Exception {
		String channel = tck.channel("bytes");
		byte[] all = new byte[256];
		for (int i = 0; i < all.length; i++) {
			all[i] = (byte) i;
		}
		List<Message> received = collect(channel);
		publisher.publish(builder().channel(channel).content(ByteBuffer.wrap(all)).buildMessage()).getValue();
		tck.awaitSize(received, 1);
		ByteBuffer payload = received.get(0).payload();
		byte[] got = new byte[payload.remaining()];
		payload.get(got);
		assertThat(got).containsExactly(all);
		assertThat(received.get(0).payload().isReadOnly()).as("received payload is read-only").isTrue();
	}

	@Test
	void emptyPayload() throws Exception {
		String channel = tck.channel("empty");
		List<Message> received = collect(channel);
		publisher.publish(builder().channel(channel).buildMessage()).getValue();
		tck.awaitSize(received, 1);
		assertThat(received.get(0).payload().remaining()).isZero();
	}

	@Test
	void publishToGivenChannel() throws Exception {
		String channel = tck.channel("given");
		List<Message> received = collect(channel);
		publisher.publish(builder().content(Tck.bytes("x")).buildMessage(), channel).getValue();
		tck.awaitSize(received, 1);
	}

	@Test
	void publishWithBuilderFunction() throws Exception {
		String channel = tck.channel("function");
		List<Message> received = collect(channel);
		publisher.publish(b -> b.channel(channel).content(Tck.bytes("f")).buildMessage()).getValue();
		tck.awaitSize(received, 1);
		assertThat(Tck.text(received.get(0))).isEqualTo("f");
	}

	@Test
	void contextParameterReplacesTheMessageContext() throws Exception {
		String channel = tck.channel("override");
		String other = tck.channel("other");
		List<Message> received = collect(channel);
		List<Message> wrong = collect(other);
		Message m = text(other, "o");
		MessageContext ctx = builder().channel(channel).buildContext();
		publisher.publish(m, ctx).getValue();
		tck.awaitSize(received, 1);
		tck.settle();
		assertThat(wrong).isEmpty();
	}

	@Test
	void subscribeWithContext() throws Exception {
		String channel = tck.channel("context");
		List<Message> received = collect(builder().channel(channel).buildContext());
		publisher.publish(text(channel, "c")).getValue();
		tck.awaitSize(received, 1);
	}

	@Test
	void manyMessagesArriveInOrder() throws Exception {
		String channel = tck.channel("order");
		List<Message> received = collect(channel);
		int n = 100;
		for (int i = 0; i < n; i++) {
			publisher.publish(text(channel, Integer.toString(i))).getValue();
		}
		tck.awaitSize(received, n);
		for (int i = 0; i < n; i++) {
			assertThat(Tck.text(received.get(i))).isEqualTo(Integer.toString(i));
		}
	}

	@Test
	void everySubscriberOfATopicReceives() throws Exception {
		assumeTrue(tck.has(FEATURE_PUBLISH_SUBSCRIBE));
		String channel = tck.channel("fanout");
		List<Message> a = collect(channel);
		List<Message> b = collect(channel);
		publisher.publish(text(channel, "both")).getValue();
		tck.awaitSize(a, 1);
		tck.awaitSize(b, 1);
	}

	@Test
	void closingTheStreamEndsTheSubscription() throws Exception {
		String channel = tck.channel("close");
		PushStream<Message> stream = subscription.subscribe(channel);
		List<Message> received = collect(stream);
		publisher.publish(text(channel, "1")).getValue();
		tck.awaitSize(received, 1);
		stream.close();
		publisher.publish(text(channel, "2")).getValue();
		tck.settle();
		assertThat(received).hasSize(1);
	}

	@Test
	void otherChannelsAreNotReceived() throws Exception {
		String channel = tck.channel("mine");
		List<Message> received = collect(channel);
		publisher.publish(text(tck.channel("theirs"), "no")).getValue();
		publisher.publish(text(channel, "yes")).getValue();
		tck.awaitSize(received, 1);
		tck.settle();
		assertThat(received).extracting(Tck::text).containsExactly("yes");
	}

}
