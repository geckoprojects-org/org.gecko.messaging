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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.gecko.messaging.api.MessagingConstants.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.gecko.messaging.api.FeatureNotSupportedException;
import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.MessagingException;
import org.gecko.messaging.api.acknowledge.AcknowledgeMessageContext;
import org.gecko.messaging.api.acknowledge.AcknowledgeMessageContextBuilder;
import org.gecko.messaging.api.acknowledge.AcknowledgeType;
import org.gecko.messaging.api.cloudevents.CloudEventMessageContext;
import org.gecko.messaging.api.cloudevents.CloudEventMessageContextBuilder;
import org.gecko.messaging.api.runtime.ConnectionEvent;
import org.gecko.messaging.api.runtime.ConnectionState;
import org.gecko.messaging.core.memory.InMemoryBroker;
import org.gecko.messaging.core.memory.InMemoryInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.osgi.util.promise.Promise;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamProvider;

class InMemoryInstanceTest {

	private final List<InMemoryInstance> instances = new ArrayList<>();
	private String broker;

	@BeforeEach
	void setUp() {
		broker = "test-" + UUID.randomUUID();
	}

	@AfterEach
	void tearDown() {
		instances.forEach(InMemoryInstance::stop);
		InMemoryBroker.remove(broker);
	}

	private InMemoryInstance start(Map<String, Object> extra) throws Exception {
		Map<String, Object> props = new HashMap<>();
		props.put(MESSAGING_NAME_PROPERTY, "i" + instances.size());
		props.put("uri", "memory://" + broker);
		props.put("reconnectDelay", 20L);
		props.put("reconnectMaxDelay", 50L);
		props.put("replyTimeout", 2000L);
		props.putAll(extra);
		InMemoryInstance i = new InMemoryInstance(new MessagingConfiguration(props));
		instances.add(i);
		i.startStandalone();
		await(() -> i.getConnectionState() == ConnectionState.CONNECTED);
		return i;
	}

	private InMemoryInstance start() throws Exception {
		return start(Map.of());
	}

	private static void await(BooleanSupplier condition) throws InterruptedException {
		long end = System.currentTimeMillis() + 5000;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > end) {
				throw new AssertionError("Condition not met in time");
			}
			Thread.sleep(5);
		}
	}

	private static Message text(InMemoryInstance i, String channel, String text) {
		return i.newBuilder().channel(channel).content(ByteBuffer.wrap(text.getBytes(UTF_8))).buildMessage();
	}

	private static String text(Message m) {
		return new String(SimpleMessage.bytes(m), UTF_8);
	}

	private static List<Message> collect(PushStream<Message> stream) {
		List<Message> received = new CopyOnWriteArrayList<>();
		stream.forEach(received::add);
		return received;
	}

	@Test
	void publishAndSubscribe() throws Exception {
		InMemoryInstance i = start();
		List<Message> received = collect(i.subscribe("a/b"));
		await(() -> i.getRuntimeDTO().subscriptions.length == 1);
		i.publish(i.newBuilder().channel("a/b").contentType("text/plain").header("h", 1).content(ByteBuffer.wrap("x".getBytes()))
				.buildMessage(), null).getValue();
		await(() -> received.size() == 1);
		Message m = received.get(0);
		assertThat(text(m)).isEqualTo("x");
		assertThat(m.getContext().getChannel()).isEqualTo("a/b");
		assertThat(m.getContext().getContentType()).isEqualTo("text/plain");
		assertThat(m.getContext().getHeaders()).containsEntry("h", 1);
		assertThat(m.payload().isReadOnly()).isTrue();
	}

	@Test
	void payloadIsCopiedAndIndependent() {
		ByteBuffer source = ByteBuffer.wrap("abc".getBytes(UTF_8));
		Message m = new SimpleMessageContextBuilder().channel("c").content(source).buildMessage();
		source.put(0, (byte) 'z');
		ByteBuffer first = m.payload();
		first.get();
		assertThat(text(m)).isEqualTo("abc");
		assertThat(m.payload().position()).isZero();
	}

	@Test
	void wildcardsAndClosingUnsubscribes() throws Exception {
		InMemoryInstance i = start();
		PushStream<Message> stream = i.subscribe("s/+/t");
		List<Message> received = collect(stream);
		await(() -> i.getRuntimeDTO().subscriptions.length == 1);
		i.publish(text(i, "s/1/t", "1"), null).getValue();
		i.publish(text(i, "s/1/x", "no"), null).getValue();
		await(() -> received.size() == 1);
		stream.close();
		await(() -> i.getRuntimeDTO().subscriptions.length == 0);
		i.publish(text(i, "s/2/t", "2"), null).getValue();
		Thread.sleep(50);
		assertThat(received).hasSize(1);
	}

	@Test
	void subscriptionsSurviveReconnectAndAreRestoredBeforeTheEvent() throws Exception {
		InMemoryInstance i = start();
		List<Message> received = collect(i.subscribe("r"));
		await(() -> i.getRuntimeDTO().subscriptions.length == 1);
		List<ConnectionEvent> events = new CopyOnWriteArrayList<>();
		List<Integer> subscriptionsAtReconnect = new CopyOnWriteArrayList<>();
		i.addConnectionListener(e -> {
			events.add(e);
			if (e.reconnect()) {
				subscriptionsAtReconnect.add(i.getRuntimeDTO().subscriptions[0].channel.connected ? 1 : 0);
			}
		});
		i.broker().dropConnections();
		await(() -> events.stream().anyMatch(ConnectionEvent::reconnect));
		assertThat(events).extracting(ConnectionEvent::state).containsSubsequence(ConnectionState.DISCONNECTED,
				ConnectionState.CONNECTED);
		assertThat(subscriptionsAtReconnect).containsExactly(1);
		assertThat(i.getRuntimeDTO().reconnects).isEqualTo(1);
		i.publish(text(i, "r", "after"), null).getValue();
		await(() -> received.size() == 1);
	}

	@Test
	void reconnectRetriesUntilTheBrokerIsBack() throws Exception {
		InMemoryInstance i = start();
		i.broker().setAvailable(false);
		await(() -> i.getConnectionState() == ConnectionState.DISCONNECTED);
		Thread.sleep(150); // several failed attempts
		assertThat(i.getConnectionState()).isEqualTo(ConnectionState.DISCONNECTED);
		Promise<Void> p = i.publish(text(i, "x", "y"), null);
		assertThat(p.getFailure()).isInstanceOf(MessagingException.class);
		i.broker().setAvailable(true);
		await(() -> i.getConnectionState() == ConnectionState.CONNECTED);
	}

	@Test
	void unsupportedFeaturesFail() throws Exception {
		Map<String, Object> props = new HashMap<>();
		props.put(MESSAGING_NAME_PROPERTY, "limited");
		props.put("uri", "memory://" + broker);
		AbstractMessagingInstance limited = new AbstractMessagingInstance("memory", new MessagingConfiguration(props),
				List.of(FEATURE_PUBLISH_SUBSCRIBE)) {
			@Override
			protected boolean doConnect(long timeoutMillis) {
				return false;
			}

			@Override
			protected void doDisconnect() {
			}

			@Override
			protected Promise<Void> doPublish(SimpleMessageContext context, ByteBuffer payload) {
				return promises.resolved(null);
			}

			@Override
			protected void doSubscribe(SubscriptionHandle handle) {
			}

			@Override
			protected void doUnsubscribe(SubscriptionHandle handle) {
			}
		};
		try {
			limited.startStandalone();
			await(() -> limited.getConnectionState() == ConnectionState.CONNECTED);
			MessageContext qos = limited.newBuilder().channel("c").extensionEntry(EXTENSION_QOS, 1).buildContext();
			assertThat(limited.publish(plain("c", "x"), qos).getFailure()).isInstanceOf(FeatureNotSupportedException.class);
			assertThatThrownBy(() -> limited.subscribe(qos)).isInstanceOf(FeatureNotSupportedException.class);
			MessageContext headers = limited.newBuilder().channel("c").header("h", "v").buildContext();
			assertThat(((FeatureNotSupportedException) limited.publish(plain("c", "x"), headers).getFailure())
					.getFeature()).isEqualTo(FEATURE_HEADERS);
			assertThat(limited.newBuilder().isAdaptable(AcknowledgeMessageContextBuilder.class)).isFalse();
			assertThatThrownBy(() -> limited.newBuilder().adapt(AcknowledgeMessageContextBuilder.class))
					.isInstanceOf(FeatureNotSupportedException.class);
			assertThat(limited.publish(plain("c", "plain"), null).getFailure()).isNull();
		} finally {
			limited.stop();
		}
	}

	private static Message plain(String channel, String text) {
		return new SimpleMessageContextBuilder().channel(channel).content(ByteBuffer.wrap(text.getBytes(UTF_8))).buildMessage();
	}

	@Test
	void acknowledgeFilterRejectsBeforeTheStream() throws Exception {
		InMemoryInstance i = start();
		List<Message> rejected = new CopyOnWriteArrayList<>();
		List<Message> acked = new CopyOnWriteArrayList<>();
		MessageContext ctx = i.newBuilder().channel("ack").adapt(AcknowledgeMessageContextBuilder.class)
				.acknowledgeFilter(m -> text(m).startsWith("ok")).postAcknowledge(acked::add).postReject(rejected::add)
				.messageContextBuilder().buildContext();
		List<Message> received = collect(i.subscribe(ctx));
		await(() -> i.getRuntimeDTO().subscriptions.length == 1);
		i.publish(text(i, "ack", "ok-1"), null).getValue();
		i.publish(text(i, "ack", "bad"), null).getValue();
		i.publish(text(i, "ack", "ok-2"), null).getValue();
		await(() -> received.size() == 2 && rejected.size() == 1);
		assertThat(received).extracting(InMemoryInstanceTest::text).containsExactly("ok-1", "ok-2");
		assertThat(acked).hasSize(2);
		assertThat(((AcknowledgeMessageContext) received.get(0).getContext()).getAcknowledgeState())
				.isEqualTo(AcknowledgeType.ACKNOWLEDGED);
	}

	@Test
	void anInstanceWithCloudEventsTakesCloudEventsOnlyByDefault() throws Exception {
		InMemoryInstance plainSender = start();
		for (String mode : new String[] { "structured", "binary" }) {
			InMemoryInstance strict = start(Map.of("cloudEvents", mode));
			List<Message> received = collect(strict.subscribe("ce/" + mode));
			await(() -> strict.getRuntimeDTO().subscriptions.length == 1);
			plainSender.publish(text(plainSender, "ce/" + mode, "{\"temp\": 21}"), null).getValue();
			await(() -> strict.getRuntimeDTO().subscriptions[0].droppedMessages == 1);
			// a CloudEvent on the same channel still arrives, so the plain one was dropped, not late
			strict.publish(text(strict, "ce/" + mode, "event"), null).getValue();
			await(() -> received.size() == 1);
			assertThat(received.get(0).getContext()).as(mode).isInstanceOf(CloudEventMessageContext.class);
			assertThat(text(received.get(0))).isEqualTo("event");
		}
	}

	@Test
	void acceptPlainDeliversPlainMessagesUnchanged() throws Exception {
		InMemoryInstance plainSender = start();
		InMemoryInstance tolerant = start(Map.of("cloudEvents", "structured", "cloudEvents.acceptPlain", true));
		List<Message> received = collect(tolerant.subscribe("ce/tolerant"));
		await(() -> tolerant.getRuntimeDTO().subscriptions.length == 1);
		plainSender.publish(text(plainSender, "ce/tolerant", "{\"temp\": 21}"), null).getValue();
		await(() -> received.size() == 1);
		assertThat(received.get(0).getContext()).as("nothing made up for it").isNotInstanceOf(CloudEventMessageContext.class);
		assertThat(text(received.get(0))).isEqualTo("{\"temp\": 21}");
		assertThat(tolerant.getRuntimeDTO().subscriptions[0].droppedMessages).isZero();
	}

	@Test
	void anExpiredRetainedMessageReachesNoLateSubscriber() throws Exception {
		InMemoryInstance i = start();
		MessageContext shortLived = i.newBuilder().channel("kept/short").extensionEntry(EXTENSION_RETAIN, true)
				.extensionEntry(EXTENSION_TIME_TO_LIVE, 50L).buildContext();
		MessageContext lasting = i.newBuilder().channel("kept/long").extensionEntry(EXTENSION_RETAIN, true).buildContext();
		i.publish(text(i, "kept/short", "gone"), shortLived).getValue();
		i.publish(text(i, "kept/long", "here"), lasting).getValue();
		Thread.sleep(150);
		List<Message> late = collect(i.subscribe("kept/#"));
		await(() -> late.size() >= 1);
		Thread.sleep(200);
		assertThat(late).extracting(InMemoryInstanceTest::text).containsExactly("here");
	}

	@Test
	void aFailingDecisionRejectsWithoutRequeue() throws Exception {
		InMemoryInstance i = start();
		AtomicInteger calls = new AtomicInteger();
		MessageContext ctx = i.newBuilder().channel("poison").extensionEntry(EXTENSION_CHANNEL_TYPE, CHANNEL_TYPE_QUEUE)
				.adapt(AcknowledgeMessageContextBuilder.class).requeueOnReject(true).acknowledgeFilter(m -> {
					calls.incrementAndGet();
					throw new IllegalStateException("cannot decide");
				}).messageContextBuilder().buildContext();
		i.subscribe(ctx).forEach(m -> {
		});
		await(() -> i.getRuntimeDTO().subscriptions.length == 1);
		i.publish(i.newBuilder().channel("poison").extensionEntry(EXTENSION_CHANNEL_TYPE, CHANNEL_TYPE_QUEUE)
				.content(ByteBuffer.wrap("x".getBytes(UTF_8))).buildMessage(), null).getValue();
		await(() -> calls.get() >= 1);
		Thread.sleep(300);
		assertThat(calls.get()).as("not re-queued into a loop").isEqualTo(1);
	}

	@Test
	void manualAcknowledgeWithRequeue() throws Exception {
		InMemoryInstance i = start();
		MessageContext ctx = i.newBuilder().channel("jobs").extensionEntry(EXTENSION_CHANNEL_TYPE, CHANNEL_TYPE_QUEUE)
				.adapt(AcknowledgeMessageContextBuilder.class).manualAcknowledge().requeueOnReject(true).messageContextBuilder()
				.buildContext();
		List<String> seen = new CopyOnWriteArrayList<>();
		CountDownLatch done = new CountDownLatch(1);
		i.subscribe(ctx).forEach(m -> {
			AcknowledgeMessageContext ack = (AcknowledgeMessageContext) m.getContext();
			seen.add(text(m));
			if (seen.size() == 1) {
				assertThat(ack.getAcknowledgeHandler().reject()).isTrue();
				assertThat(ack.getAcknowledgeHandler().acknowledge()).isFalse();
			} else {
				ack.getAcknowledgeHandler().acknowledge();
				done.countDown();
			}
		});
		await(() -> i.getRuntimeDTO().subscriptions.length == 1);
		i.publish(i.newBuilder().channel("jobs").extensionEntry(EXTENSION_CHANNEL_TYPE, CHANNEL_TYPE_QUEUE)
				.content(ByteBuffer.wrap("job".getBytes(UTF_8))).buildMessage(), null).getValue();
		assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(seen).containsExactly("job", "job");
	}

	@Test
	void queueDeliversToOneSubscriber() throws Exception {
		InMemoryInstance i = start();
		MessageContext q = i.newBuilder().channel("work").extensionEntry(EXTENSION_CHANNEL_TYPE, CHANNEL_TYPE_QUEUE).buildContext();
		List<Message> a = collect(i.subscribe(q));
		List<Message> b = collect(i.subscribe(q));
		await(() -> i.getRuntimeDTO().subscriptions.length == 2);
		for (int n = 0; n < 10; n++) {
			i.publish(i.newBuilder().withContext(q).content(ByteBuffer.wrap(("" + n).getBytes())).buildMessage(), null).getValue();
		}
		await(() -> a.size() + b.size() == 10);
		assertThat(a).isNotEmpty();
		assertThat(b).isNotEmpty();
	}

	@Test
	void replyToWithGeneratedCorrelationAndChannel() throws Exception {
		InMemoryInstance i = start();
		i.subscribe("service").forEach(request -> {
			MessageContext rc = request.getContext();
			Message response = i.newBuilder().channel(rc.getReplyToChannel()).correlationId(rc.getCorrelationId())
					.content(ByteBuffer.wrap((text(request) + "-pong").getBytes(UTF_8))).buildMessage();
			i.publish(response, null);
		});
		await(() -> i.getRuntimeDTO().subscriptions.length == 1);
		Message response = i.replyToPublisher().publishWithReply(text(i, "service", "ping")).getValue();
		assertThat(text(response)).isEqualTo("ping-pong");
		// the shared reply channel is internal and not listed
		assertThat(i.getRuntimeDTO().subscriptions).hasSize(1);
		Message second = i.replyToPublisher().publishWithReply(text(i, "service", "again")).getValue();
		assertThat(text(second)).isEqualTo("again-pong");
	}

	@Test
	void stopRightAfterAReplyDoesNotFail() throws Exception {
		// stop() fails the exchanges still pending; one whose response just arrived must not be
		// failed a second time (the dispatcher resolves before it removes the exchange)
		for (int n = 0; n < 200; n++) {
			InMemoryInstance i = start();
			i.subscribe("service").forEach(request -> {
				MessageContext rc = request.getContext();
				i.publish(i.newBuilder().channel(rc.getReplyToChannel()).correlationId(rc.getCorrelationId())
						.content(ByteBuffer.wrap("pong".getBytes(UTF_8))).buildMessage(), null);
			});
			await(() -> i.getRuntimeDTO().subscriptions.length == 1);
			assertThat(text(i.replyToPublisher().publishWithReply(text(i, "service", "ping")).getValue())).isEqualTo("pong");
			i.stop();
			instances.remove(i);
		}
	}

	@Test
	void duplicateResponseIsIgnored() throws Exception {
		InMemoryInstance i = start();
		i.subscribe("service").forEach(request -> {
			MessageContext rc = request.getContext();
			for (String answer : List.of("first", "second")) {
				i.publish(i.newBuilder().channel(rc.getReplyToChannel()).correlationId(rc.getCorrelationId())
						.content(ByteBuffer.wrap(answer.getBytes(UTF_8))).buildMessage(), null);
			}
		});
		await(() -> i.getRuntimeDTO().subscriptions.length == 1);
		assertThat(text(i.replyToPublisher().publishWithReply(text(i, "service", "ping")).getValue())).isEqualTo("first");
	}

	@Test
	void replyToTimesOut() throws Exception {
		InMemoryInstance i = start();
		MessageContext ctx = i.newBuilder().channel("nobody").extensionEntry(EXTENSION_REPLY_TIMEOUT, 100L).buildContext();
		Promise<Message> p = i.replyToPublisher().publishWithReply(text(i, "nobody", "hello"), ctx);
		assertThat(p.getFailure()).isInstanceOf(TimeoutException.class);
	}

	@Test
	void replyToWithOwnChannelUnsubscribesAfterwards() throws Exception {
		InMemoryInstance i = start();
		i.subscribe("svc").forEach(request -> i.publish(i.newBuilder().channel(request.getContext().getReplyToChannel())
				.correlationId(request.getContext().getCorrelationId()).content(ByteBuffer.wrap("r".getBytes())).buildMessage(), null));
		await(() -> i.getRuntimeDTO().subscriptions.length == 1);
		MessageContext ctx = i.newBuilder().channel("svc").replyTo("my/replies").correlationId("c-1").buildContext();
		assertThat(text(i.replyToPublisher().publishWithReply(text(i, "svc", "q"), ctx).getValue())).isEqualTo("r");
		assertThat(i.getRuntimeDTO().subscriptions).hasSize(1);
	}

	@Test
	void replyToManyEndsWithTheCompleteHeader() throws Exception {
		InMemoryInstance i = start();
		i.subscribe("count").forEach(request -> {
			MessageContext rc = request.getContext();
			for (int n = 1; n <= 3; n++) {
				i.publish(i.newBuilder().channel(rc.getReplyToChannel()).correlationId(rc.getCorrelationId())
						.content(ByteBuffer.wrap(("" + n).getBytes())).buildMessage(), null);
			}
			i.publish(i.newBuilder().channel(rc.getReplyToChannel()).correlationId(rc.getCorrelationId())
					.header(HEADER_REPLY_COMPLETE, true).buildMessage(), null);
		});
		await(() -> i.getRuntimeDTO().subscriptions.length == 1);
		List<String> responses = Collections.synchronizedList(new ArrayList<>());
		Promise<Void> done = i.replyToPublisher().publishWithReplyMany(text(i, "count", "go")).map(InMemoryInstanceTest::text)
				.forEach(responses::add);
		done.getValue();
		assertThat(responses).containsExactly("1", "2", "3");
	}

	@Test
	void cloudEventsStructured() throws Exception {
		InMemoryInstance i = start(Map.of("cloudEvents", "structured"));
		assertThat(i.getFeatures().has(FEATURE_CLOUD_EVENTS)).isTrue();
		List<Message> received = collect(i.subscribe("ce"));
		await(() -> i.getRuntimeDTO().subscriptions.length == 1);
		Message m = i.newBuilder().channel("ce").contentType("application/json").header("tenant", "a").correlationId("c")
				.content(ByteBuffer.wrap("{\"v\":1}".getBytes(UTF_8))).adapt(CloudEventMessageContextBuilder.class).type("t.x")
				.source("/s").subject("sub").messageContextBuilder().buildMessage();
		i.publish(m, null).getValue();
		await(() -> received.size() == 1);
		Message r = received.get(0);
		assertThat(r.getContext()).isInstanceOf(CloudEventMessageContext.class);
		CloudEventMessageContext ce = (CloudEventMessageContext) r.getContext();
		assertThat(ce.getCloudEvent().getType()).isEqualTo("t.x");
		assertThat(ce.getCloudEvent().getSubject()).isEqualTo("sub");
		assertThat(ce.getContentType()).isEqualTo("application/json");
		assertThat(ce.getCorrelationId()).isEqualTo("c");
		assertThat(ce.getHeaders()).containsEntry("tenant", "a");
		assertThat(text(r)).isEqualTo("{\"v\":1}");
	}

	@Test
	void cloudEventsBinaryWrapsPlainMessages() throws Exception {
		InMemoryInstance i = start(Map.of("cloudEvents", "binary", "cloudEvents.source", "/me"));
		List<Message> received = collect(i.subscribe("ce"));
		await(() -> i.getRuntimeDTO().subscriptions.length == 1);
		i.publish(i.newBuilder().channel("ce").header("k", "v").content(ByteBuffer.wrap(new byte[] { 1, 2 })).buildMessage(), null)
				.getValue();
		await(() -> received.size() == 1);
		CloudEventMessageContext ce = (CloudEventMessageContext) received.get(0).getContext();
		assertThat(ce.getCloudEvent().getSource()).isEqualTo("/me");
		assertThat(ce.getCloudEvent().getExtensions()).containsEntry("k", "v");
		assertThat(SimpleMessage.bytes(received.get(0))).containsExactly(1, 2);
	}

	@Test
	void defaultContextFromConfiguration() throws Exception {
		InMemoryInstance i = start(Map.of("context.channel", "configured", "context.contentType", "text/plain"));
		List<Message> received = collect(i.subscribe((MessageContext) null));
		await(() -> i.getRuntimeDTO().subscriptions.length == 1);
		i.publish(new SimpleMessageContextBuilder().content(ByteBuffer.wrap("d".getBytes())).buildMessage(), null).getValue();
		await(() -> received.size() == 1);
		assertThat(received.get(0).getContext().getChannel()).isEqualTo("configured");
	}

	@Test
	void stopClosesStreamsAndThreads() throws Exception {
		InMemoryInstance i = start();
		PushStream<Message> stream = i.subscribe("x");
		Promise<Long> end = stream.count();
		await(() -> i.getRuntimeDTO().subscriptions.length == 1);
		i.stop();
		assertThat(end.getValue()).isZero();
		assertThat(i.getConnectionState()).isEqualTo(ConnectionState.CLOSED);
		assertThat(i.broker().connectionCount()).isZero();
	}

	@Test
	void discardOldestRejectsAndCountsWhatItDiscards() throws Exception {
		InMemoryInstance i = start(Map.of("queuePolicy", "DISCARD_OLDEST", "bufferSize", 1L));
		List<Message> rejected = new CopyOnWriteArrayList<>();
		MessageContext ctx = i.newBuilder().channel("slow").adapt(AcknowledgeMessageContextBuilder.class).manualAcknowledge()
				.postReject(rejected::add).messageContextBuilder().buildContext();
		CountDownLatch release = new CountDownLatch(1);
		List<String> handled = new CopyOnWriteArrayList<>();
		i.subscribe(ctx).forEach(m -> {
			try {
				release.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			handled.add(text(m));
			((AcknowledgeMessageContext) m.getContext()).getAcknowledgeHandler().acknowledge();
		});
		await(() -> i.getRuntimeDTO().subscriptions.length == 1);
		for (int n = 0; n < 6; n++) {
			i.publish(text(i, "slow", "m" + n), null).getValue();
		}
		// at most one in the handler and one in the buffer of one place: the others are discarded
		await(() -> rejected.size() >= 4);
		assertThat(i.getRuntimeDTO().subscriptions[0].droppedMessages).isEqualTo(rejected.size());
		assertThat(rejected).allSatisfy(
				m -> assertThat(((AcknowledgeMessageContext) m.getContext()).getAcknowledgeState()).isEqualTo(AcknowledgeType.REJECTED));
		release.countDown();
		await(() -> handled.size() + rejected.size() == 6);
		assertThat(handled).as("the newest is kept").contains("m5");
	}

	/** A subscriber blocked until released, recording what it handles. */
	private static final class Gate {
		final CountDownLatch release = new CountDownLatch(1);
		final List<String> handled = new CopyOnWriteArrayList<>();

		void handle(Message m) {
			try {
				release.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			handled.add(text(m));
		}
	}

	@Test
	void aSubscriptionHasItsOwnBufferAndQueuePolicy() throws Exception {
		// the instance blocks; this subscription keeps the latest two and counts the rest
		InMemoryInstance i = start(Map.of("bufferSize", 64L));
		MessageContext ctx = i.newBuilder().channel("own").extensionEntry(EXTENSION_BUFFER_SIZE, 2)
				.extensionEntry(EXTENSION_QUEUE_POLICY, "discard_oldest").buildContext();
		Gate gate = new Gate();
		i.subscribe(ctx).forEach(gate::handle);
		await(() -> i.getRuntimeDTO().subscriptions.length == 1);
		for (int n = 0; n < 10; n++) {
			i.publish(text(i, "own", "m" + n), null).getValue();
		}
		await(() -> i.getRuntimeDTO().subscriptions[0].droppedMessages >= 7);
		var dto = i.getRuntimeDTO().subscriptions[0];
		assertThat(dto.bufferSize).isEqualTo(2);
		assertThat(dto.bufferedMessages).isLessThanOrEqualTo(2);
		gate.release.countDown();
		await(() -> gate.handled.contains("m9"));
	}

	@Test
	void theBufferIsBoundedInBytes() throws Exception {
		InMemoryInstance i = start(Map.of("queuePolicy", "DISCARD_OLDEST"));
		MessageContext ctx = i.newBuilder().channel("bytes").extensionEntry(EXTENSION_BUFFER_BYTES, 3000L).buildContext();
		Gate gate = new Gate();
		i.subscribe(ctx).forEach(gate::handle);
		await(() -> i.getRuntimeDTO().subscriptions.length == 1);
		for (int n = 0; n < 10; n++) {
			byte[] payload = new byte[1000];
			payload[0] = (byte) n;
			i.publish(i.newBuilder().channel("bytes").content(ByteBuffer.wrap(payload)).buildMessage(), null).getValue();
		}
		// one in the handler, at most three of 1000 bytes in the buffer
		await(() -> i.getRuntimeDTO().subscriptions[0].droppedMessages >= 6);
		var dto = i.getRuntimeDTO().subscriptions[0];
		assertThat(dto.bufferBytes).isEqualTo(3000);
		assertThat(dto.bufferedBytes).isLessThanOrEqualTo(3000);
		gate.release.countDown();
	}

	@Test
	void discardOldestDiscardsWholeGroups() throws Exception {
		InMemoryInstance i = start(Map.of("queuePolicy", "DISCARD_OLDEST"));
		MessageContext ctx = i.newBuilder().channel("video").extensionEntry(EXTENSION_BUFFER_SIZE, 4)
				.extensionEntry(EXTENSION_GROUP_HEADER, "keyframe").buildContext();
		Gate gate = new Gate();
		i.subscribe(ctx).forEach(gate::handle);
		await(() -> i.getRuntimeDTO().subscriptions.length == 1);
		// frames: K0 P1 P2 | K3 P4 P5 | K6 P7 ...; the first one waits in the handler
		for (int n = 0; n < 9; n++) {
			boolean key = n % 3 == 0;
			i.publish(i.newBuilder().channel("video").header("keyframe", key).content(ByteBuffer.wrap(((key ? "K" : "P") + n).getBytes(UTF_8)))
					.buildMessage(), null).getValue();
		}
		await(() -> i.getRuntimeDTO().subscriptions[0].droppedMessages >= 3);
		gate.release.countDown();
		await(() -> gate.handled.contains("P8"));
		// every gap in what arrives ends at a key frame: no frame without its group start
		List<String> handled = gate.handled;
		for (int n = 1; n < handled.size(); n++) {
			int previous = Integer.parseInt(handled.get(n - 1).substring(1));
			int current = Integer.parseInt(handled.get(n).substring(1));
			if (current != previous + 1) {
				assertThat(handled.get(n)).as("after a gap: %s", handled).startsWith("K");
			}
		}
	}

	@Test
	void subscriptionSettingsAboveTheInstanceAreRefused() throws Exception {
		InMemoryInstance i = start(Map.of("bufferSize", 8L));
		MessageContext tooLarge = i.newBuilder().channel("x").extensionEntry(EXTENSION_BUFFER_SIZE, 9).buildContext();
		assertThatThrownBy(() -> i.subscribe(tooLarge)).isInstanceOf(MessagingException.class);
		MessageContext unknown = i.newBuilder().channel("x").extensionEntry(EXTENSION_QUEUE_POLICY, "LATEST").buildContext();
		assertThatThrownBy(() -> i.subscribe(unknown)).isInstanceOf(MessagingException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "1", "virtual" })
	void aHandlerWaitingForAReplyDoesNotStarveItsInstance(String workerThreads) throws Exception {
		InMemoryInstance requester = start(Map.of("workerThreads", workerThreads));
		InMemoryInstance responder = start();
		responder.subscribe("svc").forEach(request -> responder.publish(text(responder, "unused", "answer"),
				responder.newBuilder().channel(request.getContext().getReplyToChannel())
						.correlationId(request.getContext().getCorrelationId()).buildContext()));
		List<String> answers = new CopyOnWriteArrayList<>();
		// the handler blocks its worker until the reply arrives: the promise must not need a worker
		requester.subscribe("trigger").forEach(m -> {
			try {
				// chained: the mapping runs as a promise callback, which needs a thread of its own
				answers.add(requester.replyToPublisher().publishWithReply(text(requester, "svc", "question"))
						.map(InMemoryInstanceTest::text).getValue());
			} catch (Exception e) {
				answers.add("failed: " + e);
			}
		});
		await(() -> requester.getRuntimeDTO().subscriptions.length == 1 && responder.getRuntimeDTO().subscriptions.length == 1);
		for (int n = 0; n < 3; n++) {
			requester.publish(text(requester, "trigger", "t" + n), null).getValue();
		}
		await(() -> answers.size() == 3);
		assertThat(answers).containsOnly("answer");
	}

	@Test
	void aStreamPublishCanSkipFailedMessages() throws Exception {
		InMemoryInstance i = start(Map.of("streamPublishFailures", "continue"));
		List<Message> received = collect(i.subscribe("skip"));
		await(() -> i.getRuntimeDTO().subscriptions.length == 1);
		// the third message has an invalid quality of service and fails
		PushStream<Message> stream = new PushStreamProvider().streamOf(IntStream.range(0, 5)
				.mapToObj(n -> n == 2 ? i.newBuilder().channel("skip").extensionEntry(EXTENSION_QOS, 9).content(ByteBuffer.wrap(new byte[1])).buildMessage()
						: text(i, "skip", "m" + n)));
		assertThat(i.publish(stream, null).getValue()).isEqualTo(4);
		assertThat(i.getRuntimeDTO().streamPublishFailures).isEqualTo(1);
		await(() -> received.size() == 4);
	}

	@Test
	void anUnknownStreamFailureModeIsRefused() throws Exception {
		InMemoryInstance i = start(Map.of("streamPublishFailures", "ignore"));
		PushStream<Message> stream = new PushStreamProvider().streamOf(Stream.of(text(i, "x", "m")));
		assertThat(i.publish(stream, null).getFailure()).isInstanceOf(MessagingException.class);
	}

}
