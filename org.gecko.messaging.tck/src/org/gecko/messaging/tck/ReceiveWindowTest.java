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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.dto.SubscriptionDTO;
import org.gecko.messaging.api.runtime.MessageServiceRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.osgi.util.pushstream.PushStream;

/**
 * The receive window follows the buffer (§8): the broker stops sending before a buffer is full,
 * so that a full {@code BLOCK} buffer does not hold the protocol's thread. Bindings without a
 * window set by the client report {@code 0} and skip.
 */
public class ReceiveWindowTest extends AbstractMessagingTest {

	private Sibling sibling;

	@AfterEach
	void removeSibling() throws Exception {
		if (sibling != null) {
			sibling.close();
			sibling = null;
		}
	}

	private SubscriptionDTO subscriptionOf(MessageServiceRuntime runtime, String channel) {
		SubscriptionDTO[] found = new SubscriptionDTO[1];
		tck.await("subscription on " + channel, () -> {
			found[0] = Arrays.stream(runtime.getRuntimeDTO().subscriptions)
					.filter(d -> channel.equals(d.channel.name) && d.channel.connected).findFirst().orElse(null);
			return found[0] != null;
		});
		return found[0];
	}

	@Test
	void theWindowIsLoweredToTheBufferSize() throws Exception {
		// both keys: each binding reads the one of its protocol
		sibling = new Sibling(tck, "window", Map.of("bufferSize", 10L, "receiveMaximum", 100L, "prefetch", 100L));
		sibling.awaitConnected();
		int window = sibling.runtime().getRuntimeDTO().receiveWindow;
		assumeTrue(window > 0, "the client sets no receive window");
		assertThat(window).isEqualTo(10);
	}

	@Test
	void aStalledSubscriberWithASmallBufferBlocksNoDelivery() throws Exception {
		MessageServiceRuntime runtime = tck.service(MessageServiceRuntime.class);
		String stalledChannel = tck.channel("window", "stalled");
		String otherChannel = tck.channel("window", "other");
		CountDownLatch release = new CountDownLatch(1);
		PushStream<Message> stalled = track(subscription.subscribe(builder().channel(stalledChannel)
				.extensionEntry(EXTENSION_BUFFER_SIZE, 3).extensionEntry(EXTENSION_QUEUE_POLICY, "block").buildContext()));
		List<Message> taken = new CopyOnWriteArrayList<>();
		stalled.forEach(m -> {
			try {
				release.await(tck.timeout(), TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			taken.add(m);
		});
		List<Message> other = collect(otherChannel);
		SubscriptionDTO dto = subscriptionOf(runtime, stalledChannel);
		assumeTrue(dto.receiveWindow > 0 && dto.receiveWindow <= dto.bufferSize,
				"the window is one per connection or not set by the client");
		try {
			for (int n = 0; n < 20; n++) {
				publisher.publish(text(stalledChannel, "m" + n)).getValue();
			}
			publisher.publish(text(otherChannel, "x")).getValue();
			tck.awaitSize(other, 1);
			dto = subscriptionOf(runtime, stalledChannel);
			assertThat(dto.bufferedMessages).isLessThanOrEqualTo(dto.bufferSize);
			assertThat(dto.blockedMillis).as("the broker stopped sending before the buffer was full").isZero();
		} finally {
			release.countDown();
		}
		tck.awaitSize(taken, 20);
	}

}
