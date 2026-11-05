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
import java.util.concurrent.CopyOnWriteArrayList;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.acknowledge.AcknowledgeHandler;
import org.gecko.messaging.api.acknowledge.AcknowledgeMessageContext;
import org.gecko.messaging.api.acknowledge.AcknowledgeMessageContextBuilder;
import org.gecko.messaging.api.acknowledge.AcknowledgeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Acknowledgement and rejection (MSG240), in the modes of {@link AcknowledgeMessageContext}.
 */
public class AcknowledgeTest extends AbstractMessagingTest {

	@BeforeEach
	void needsAcknowledge() {
		assumeTrue(tck.has(FEATURE_ACKNOWLEDGE));
	}

	private AcknowledgeMessageContextBuilder ack(String channel) {
		return builder().channel(channel).adapt(AcknowledgeMessageContextBuilder.class);
	}

	@Test
	void filterDecidesBeforeTheStream() throws Exception {
		String channel = tck.channel("ack", "filter");
		List<Message> acked = new CopyOnWriteArrayList<>();
		List<Message> rejected = new CopyOnWriteArrayList<>();
		MessageContext ctx = ack(channel).acknowledgeFilter(m -> Tck.text(m).startsWith("good")).postAcknowledge(acked::add)
				.postReject(rejected::add).messageContextBuilder().buildContext();
		assertThat(ctx).isInstanceOf(AcknowledgeMessageContext.class);
		List<Message> received = collect(ctx);
		publisher.publish(text(channel, "good-1")).getValue();
		publisher.publish(text(channel, "bad")).getValue();
		publisher.publish(text(channel, "good-2")).getValue();
		tck.awaitSize(received, 2);
		tck.awaitSize(rejected, 1);
		tck.settle();
		assertThat(received).extracting(Tck::text).containsExactly("good-1", "good-2");
		assertThat(acked).hasSize(2);
		assertThat(Tck.text(rejected.get(0))).isEqualTo("bad");
		AcknowledgeMessageContext rc = (AcknowledgeMessageContext) received.get(0).getContext();
		assertThat(rc.getAcknowledgeState()).isEqualTo(AcknowledgeType.ACKNOWLEDGED);
	}

	@Test
	void functionDecidesBeforeTheStream() throws Exception {
		String channel = tck.channel("ack", "function");
		List<Message> rejected = new CopyOnWriteArrayList<>();
		MessageContext ctx = ack(channel).acknowledgeFunction(m -> {
			AcknowledgeHandler h = ((AcknowledgeMessageContext) m.getContext()).getAcknowledgeHandler();
			if (Tck.text(m).equals("drop")) {
				h.reject();
			} else {
				h.acknowledge();
			}
		}).postReject(rejected::add).messageContextBuilder().buildContext();
		List<Message> received = collect(ctx);
		publisher.publish(text(channel, "drop")).getValue();
		publisher.publish(text(channel, "keep")).getValue();
		tck.awaitSize(received, 1);
		tck.awaitSize(rejected, 1);
		assertThat(Tck.text(received.get(0))).isEqualTo("keep");
	}

	@Test
	void manualAcknowledgeInTheStream() throws Exception {
		String channel = tck.channel("ack", "manual");
		List<Message> acked = new CopyOnWriteArrayList<>();
		MessageContext ctx = ack(channel).manualAcknowledge().postAcknowledge(acked::add).messageContextBuilder().buildContext();
		List<Boolean> results = new CopyOnWriteArrayList<>();
		track(subscription.subscribe(ctx)).forEach(m -> {
			AcknowledgeMessageContext ac = (AcknowledgeMessageContext) m.getContext();
			assertThat(ac.getAcknowledgeState()).isEqualTo(AcknowledgeType.RECEIVED);
			results.add(ac.getAcknowledgeHandler().acknowledge());
			results.add(ac.getAcknowledgeHandler().acknowledge());
		});
		publisher.publish(text(channel, "m")).getValue();
		tck.awaitSize(acked, 1);
		assertThat(results).containsExactly(true, false);
	}

	@Test
	void rejectedMessageIsRequeued() throws Exception {
		assumeTrue(tck.has(FEATURE_POINT_TO_POINT));
		String channel = tck.channel("ack", "requeue");
		Object autoCreate = tck.has(FEATURE_AUTO_CREATE) ? Boolean.TRUE : null;
		MessageContext ctx = builder().channel(channel).extensionEntry(EXTENSION_CHANNEL_TYPE, CHANNEL_TYPE_QUEUE)
				.extensionEntry(EXTENSION_AUTO_CREATE, autoCreate).adapt(AcknowledgeMessageContextBuilder.class).manualAcknowledge()
				.requeueOnReject(true).messageContextBuilder().buildContext();
		List<String> seen = new CopyOnWriteArrayList<>();
		track(subscription.subscribe(ctx)).forEach(m -> {
			AcknowledgeHandler h = ((AcknowledgeMessageContext) m.getContext()).getAcknowledgeHandler();
			seen.add(Tck.text(m));
			if (seen.size() == 1) {
				h.reject();
			} else {
				h.acknowledge();
			}
		});
		MessageContext pub = builder().channel(channel).extensionEntry(EXTENSION_CHANNEL_TYPE, CHANNEL_TYPE_QUEUE)
				.extensionEntry(EXTENSION_AUTO_CREATE, autoCreate).buildContext();
		publisher.publish(builder().content(Tck.bytes("again")).buildMessage(), pub).getValue();
		tck.awaitSize(seen, 2);
		assertThat(seen).containsExactly("again", "again");
	}

	@Test
	void aMessageIsRequeuedAtMostOnce() throws Exception {
		assumeTrue(tck.has(FEATURE_POINT_TO_POINT));
		String channel = tck.channel("ack", "poison");
		Object autoCreate = tck.has(FEATURE_AUTO_CREATE) ? Boolean.TRUE : null;
		MessageContext ctx = builder().channel(channel).extensionEntry(EXTENSION_CHANNEL_TYPE, CHANNEL_TYPE_QUEUE)
				.extensionEntry(EXTENSION_AUTO_CREATE, autoCreate).adapt(AcknowledgeMessageContextBuilder.class).manualAcknowledge()
				.requeueOnReject(true).messageContextBuilder().buildContext();
		List<String> seen = new CopyOnWriteArrayList<>();
		// a receiver that rejects every message with re-queue: without a bound it would cycle for ever
		track(subscription.subscribe(ctx)).forEach(m -> {
			seen.add(Tck.text(m));
			((AcknowledgeMessageContext) m.getContext()).getAcknowledgeHandler().reject();
		});
		MessageContext pub = builder().channel(channel).extensionEntry(EXTENSION_CHANNEL_TYPE, CHANNEL_TYPE_QUEUE)
				.extensionEntry(EXTENSION_AUTO_CREATE, autoCreate).buildContext();
		publisher.publish(builder().content(Tck.bytes("poison")).buildMessage(), pub).getValue();
		tck.awaitSize(seen, 2);
		tck.settle();
		assertThat(seen).as("delivered, re-queued once, then dropped").containsExactly("poison", "poison");
	}

	@Test
	void aRejectOnATopicIsNotRequeued() throws Exception {
		String channel = tck.channel("ack", "topic");
		MessageContext rejecting = builder().channel(channel).adapt(AcknowledgeMessageContextBuilder.class).manualAcknowledge()
				.requeueOnReject(true).messageContextBuilder().buildContext();
		List<String> rejected = new CopyOnWriteArrayList<>();
		track(subscription.subscribe(rejecting)).forEach(m -> {
			rejected.add(Tck.text(m));
			((AcknowledgeMessageContext) m.getContext()).getAcknowledgeHandler().reject();
		});
		List<Message> other = collect(channel);
		publisher.publish(text(channel, "once")).getValue();
		tck.awaitSize(other, 1);
		tck.awaitSize(rejected, 1);
		tck.settle();
		// published again, it would reach every subscriber of the topic, not just the rejecting one
		assertThat(rejected).containsExactly("once");
		assertThat(other).hasSize(1);
	}

}
