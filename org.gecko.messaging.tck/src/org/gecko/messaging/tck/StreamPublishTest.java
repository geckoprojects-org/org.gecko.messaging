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

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContext;
import org.junit.jupiter.api.Test;
import org.osgi.util.promise.Promise;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamProvider;
import org.osgi.util.pushstream.SimplePushEventSource;

/**
 * Publishing a stream (#18): order, count, failure, end, and a subscription passed on.
 */
public class StreamPublishTest extends AbstractMessagingTest {

	private final PushStreamProvider streams = new PushStreamProvider();

	@Test
	void everyMessageOfAStreamIsPublishedInOrder() throws Exception {
		String channel = tck.channel("stream", "order");
		List<Message> received = collect(channel);
		int count = 200;
		Stream<Message> messages = IntStream.range(0, count).mapToObj(i -> text(channel, "m" + i));
		Promise<Long> done = publisher.publish(streams.streamOf(messages));
		assertThat(done.getValue()).isEqualTo(count);
		tck.awaitSize(received, count);
		for (int i = 0; i < count; i++) {
			assertThat(Tck.text(received.get(i))).isEqualTo("m" + i);
		}
	}

	@Test
	void aGivenContextIsUsedForEveryMessage() throws Exception {
		String channel = tck.channel("stream", "context");
		List<Message> received = collect(channel);
		MessageContext ctx = builder().channel(channel).buildContext();
		Stream<Message> messages = IntStream.range(0, 10).mapToObj(i -> builder().content(Tck.bytes("p" + i)).buildMessage());
		assertThat(publisher.publish(streams.streamOf(messages), ctx).getValue()).isEqualTo(10);
		tck.awaitSize(received, 10);
	}

	@Test
	void theFirstFailureFailsThePromiseAndClosesTheStream() throws Exception {
		String channel = tck.channel("stream", "failure");
		CountDownLatch closed = new CountDownLatch(1);
		Stream<Message> messages = IntStream.range(0, 50).mapToObj(i -> i == 5
				// an invalid quality of service fails at once on every instance
				? builder().channel(channel).extensionEntry(EXTENSION_QOS, 9).content(Tck.bytes("bad")).buildMessage()
				: text(channel, "m" + i));
		PushStream<Message> stream = streams.streamOf(messages).onClose(closed::countDown);
		Promise<Long> done = publisher.publish(stream);
		assertThat(done.getFailure()).isNotNull();
		assertThat(closed.await(tck.timeout(), TimeUnit.MILLISECONDS)).as("the stream is closed").isTrue();
	}

	@Test
	void theEndOfTheStreamEndsThePublishing() throws Exception {
		String channel = tck.channel("stream", "end");
		List<Message> received = collect(channel);
		SimplePushEventSource<Message> source = streams.createSimpleEventSource(Message.class);
		Promise<Long> done = publisher.publish(streams.createStream(source));
		source.connectPromise().getValue();
		for (int i = 0; i < 3; i++) {
			source.publish(text(channel, "m" + i));
		}
		tck.awaitSize(received, 3);
		assertThat(done.isDone()).as("open while the stream is").isFalse();
		source.endOfStream();
		assertThat(done.getValue()).isEqualTo(3);
		source.close();
	}

	@Test
	void aSubscriptionPassedOnArrivesOnTheOtherChannel() throws Exception {
		String from = tck.channel("stream", "from");
		String to = tck.channel("stream", "to");
		List<Message> received = collect(to);
		MessageContext target = builder().channel(to).buildContext();
		Promise<Long> done = publisher.publish(track(subscription.subscribe(from)), target);
		tck.settle();
		for (int i = 0; i < 50; i++) {
			publisher.publish(text(from, "m" + i)).getValue();
		}
		tck.awaitSize(received, 50);
		assertThat(Tck.text(received.get(49))).isEqualTo("m49");
		assertThat(done.isDone()).isFalse();
	}

	@Test
	void streamsAtFullSpeedShareTheSendWindowOfTheInstance() throws Exception {
		// together they would overrun the protocol's own limit (MQTT: the broker's receive
		// maximum) if each had a window of its own
		String channel = tck.channel("stream", "shared");
		List<Message> received = collect(channel);
		int count = 1000;
		Object qos = tck.has(FEATURE_QOS) ? QOS_AT_LEAST_ONCE : null;
		MessageContext ctx = builder().channel(channel).extensionEntry(EXTENSION_QOS, qos).buildContext();
		Promise<Long> first = publisher.publish(streams.streamOf(IntStream.range(0, count).mapToObj(i -> builder().content(Tck.bytes("a" + i)).buildMessage())), ctx);
		Promise<Long> second = publisher.publish(streams.streamOf(IntStream.range(0, count).mapToObj(i -> builder().content(Tck.bytes("b" + i)).buildMessage())), ctx);
		assertThat(first.getValue()).isEqualTo(count);
		assertThat(second.getValue()).isEqualTo(count);
		tck.awaitSize(received, 2 * count);
	}

}
