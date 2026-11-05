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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.concurrent.TimeoutException;

import org.gecko.messaging.api.FeatureNotSupportedException;
import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.MessageContextBuilder;
import org.junit.jupiter.api.Test;
import org.osgi.util.pushstream.PushStream;

/**
 * Point-to-point channels, wildcards and channel extensions (MSG120, MSG170, MSG180).
 */
public class ChannelTypesTest extends AbstractMessagingTest {

	@Test
	void queueDeliversEachMessageToOneReceiver() throws Exception {
		assumeTrue(tck.has(FEATURE_POINT_TO_POINT));
		MessageContext queue = builder().channel(tck.channel("queue")).extensionEntry(EXTENSION_CHANNEL_TYPE, CHANNEL_TYPE_QUEUE)
				.extensionEntry(EXTENSION_AUTO_CREATE, tck.has(FEATURE_AUTO_CREATE) ? Boolean.TRUE : null).buildContext();
		List<Message> a = collect(queue);
		List<Message> b = collect(queue);
		int n = 20;
		for (int i = 0; i < n; i++) {
			publisher.publish(builder().content(Tck.bytes(Integer.toString(i))).buildMessage(), queue).getValue();
		}
		tck.await("all queue messages", () -> a.size() + b.size() >= n);
		tck.settle();
		assertThat(a.size() + b.size()).as("each message exactly once").isEqualTo(n);
	}

	@Test
	void singleLevelWildcard() throws Exception {
		assumeTrue(tck.has(FEATURE_WILDCARD));
		List<Message> received = collect(tck.channel("w1", tck.singleWildcard()));
		publisher.publish(text(tck.channel("w1", "a"), "a")).getValue();
		publisher.publish(text(tck.channel("w1", "a", "deep"), "deep")).getValue();
		publisher.publish(text(tck.channel("w1", "b"), "b")).getValue();
		tck.awaitSize(received, 2);
		tck.settle();
		assertThat(received).extracting(Tck::text).containsExactlyInAnyOrder("a", "b");
		assertThat(received).extracting(m -> m.getContext().getChannel()).as("the concrete channel (MSG260)")
				.containsExactlyInAnyOrder(tck.channel("w1", "a"), tck.channel("w1", "b"));
	}

	@Test
	void multiLevelWildcard() throws Exception {
		assumeTrue(tck.has(FEATURE_WILDCARD));
		List<Message> received = collect(tck.channel("wn", tck.multiWildcard()));
		publisher.publish(text(tck.channel("wn", "a"), "a")).getValue();
		publisher.publish(text(tck.channel("wn", "a", "deep"), "deep")).getValue();
		tck.awaitSize(received, 2);
	}

	@Test
	void channelExtension() throws Exception {
		assumeTrue(tck.has(FEATURE_CHANNEL_EXTENSION));
		String channel = tck.channel("ext");
		Object autoCreate = tck.has(FEATURE_AUTO_CREATE) ? Boolean.TRUE : null;
		List<Message> red = collect(builder().channel(channel, "red").extensionEntry(EXTENSION_AUTO_CREATE, autoCreate).buildContext());
		List<Message> blue = collect(builder().channel(channel, "blue").extensionEntry(EXTENSION_AUTO_CREATE, autoCreate).buildContext());
		publisher.publish(builder().content(Tck.bytes("r")).buildMessage(),
				builder().channel(channel, "red").extensionEntry(EXTENSION_AUTO_CREATE, autoCreate).buildContext()).getValue();
		tck.awaitSize(red, 1);
		assertThat(red.get(0).getContext().getChannelExtension()).isEqualTo("red");
		tck.settle();
		assertThat(blue).as("routing by extension").isEmpty();
	}

	@Test
	void noAutoCreateIsKeptOrRefused() throws Exception {
		MessageContextBuilder b = builder().channel(tck.channel("missing")).extensionEntry(EXTENSION_AUTO_CREATE, false);
		if (tck.has(FEATURE_POINT_TO_POINT)) {
			b.extensionEntry(EXTENSION_CHANNEL_TYPE, CHANNEL_TYPE_QUEUE);
		}
		MessageContext ctx = b.buildContext();
		PushStream<Message> stream;
		try {
			stream = track(subscription.subscribe(ctx));
		} catch (FeatureNotSupportedException e) {
			// a protocol that creates every channel on use refuses instead of ignoring (MSG180)
			assertThat(e.getFeature()).isEqualTo(FEATURE_AUTO_CREATE);
			return;
		}
		assumeTrue(tck.has(FEATURE_POINT_TO_POINT), "a topic needs no creation");
		// the protocol keeps to it: subscribing to a queue that does not exist fails — when the
		// stream connects, or later through the stream
		Throwable failure;
		try {
			failure = stream.forEach(m -> {
			}).timeout(tck.timeout()).getFailure();
		} catch (RuntimeException e) {
			failure = e;
		}
		assertThat(failure).as("subscription to a missing queue without autoCreate").isNotNull()
				.isNotInstanceOf(TimeoutException.class);
	}

}
