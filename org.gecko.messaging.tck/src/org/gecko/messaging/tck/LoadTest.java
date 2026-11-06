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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.MessageContextBuilder;
import org.gecko.messaging.api.dto.SubscriptionDTO;
import org.gecko.messaging.api.runtime.MessageServiceRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.Timeout;
import org.osgi.util.promise.Promise;
import org.osgi.util.pushstream.PushStreamProvider;

/**
 * Load profiles (#17): the subscription streams must not become the blocker. Runs only when the
 * framework property {@code org.gecko.messaging.tck.load} is set (the {@code load.bndrun} of a
 * run project), against an instance with a short keep-alive, so that a protocol thread held up
 * by a full buffer shows as a reconnect.
 * <p>
 * Asserted is robustness only — loss, order, reconnects. Throughput and latency are reported
 * (JUnit report entries and standard output), not asserted: CI machines vary.
 * <p>
 * Properties: {@code load.scale} (factor of the durations and counts, default 1),
 * {@code load.payload} (bytes per message, default 1024), {@code load.window} (publishes
 * outstanding at once, default 50; until the streaming publish of #18 the test throttles itself).
 */
@Timeout(300)
public class LoadTest extends AbstractMessagingTest {

	private double scale;
	private int payloadSize;
	private int window;
	private TestReporter reporter;
	private String profile;

	@BeforeEach
	void needsLoad(TestReporter reporter, TestInfo info) {
		assumeTrue(property("load", null) != null, "load profiles run from load.bndrun only");
		this.scale = Double.parseDouble(property("load.scale", "1"));
		this.payloadSize = Math.max(16, Integer.parseInt(property("load.payload", "1024")));
		this.window = Integer.parseInt(property("load.window", "50"));
		this.reporter = reporter;
		this.profile = info.getDisplayName();
	}

	private String property(String key, String defaultValue) {
		String v = bundleContext.getProperty(Tck.PREFIX + key);
		return v == null ? defaultValue : v;
	}

	/**
	 * A burst of about half a buffer at full speed to a subscriber slower than the burst: the
	 * buffer and the broker absorb it.
	 */
	@Test
	void burstIsAbsorbed() throws Exception {
		int count = (int) (500 * scale);
		Run run = new Run("burst", 2_000_000L);
		long published = run.publish(count, 0);
		run.awaitAll(count, count * 2L + 10_000);
		run.report(count, published);
		run.assertComplete(count);
		assertThat(published).as("the publisher is not held back by the slow subscriber (ns)").isLessThan(run.consumed());
	}

	/** A sustained rate the subscriber keeps up with: everything arrives, nothing piles up. */
	@Test
	void sustainedLoadTheSubscriberKeepsUpWith() throws Exception {
		int rate = 500;
		int count = (int) (rate * 5 * scale);
		Run run = new Run("sustained", 0);
		long published = run.publish(count, rate);
		run.awaitAll(count, 10_000);
		run.report(count, published);
		run.assertComplete(count);
	}

	/**
	 * A sustained rate above what the subscriber takes, over several keep-alive intervals: the
	 * broker is throttled through its flow control, the connection stays up, nothing is lost.
	 */
	@Test
	void sustainedLoadAboveTheSubscriberIsThrottled() throws Exception {
		assumeTrue(tck.has(FEATURE_QOS), "needs QoS 1: without broker flow control a slow subscriber loses messages");
		int rate = 200;
		int count = (int) (rate * 6 * scale);
		// takes 125 messages per second: the backlog grows for the whole run
		Run run = new Run("slow", 8_000_000L);
		long published = run.publish(count, rate);
		run.awaitAll(count, count * 8L + 10_000);
		run.report(count, published);
		run.assertComplete(count);
		run.assertPublisherKeptItsRate(count, rate, published);
	}

	/**
	 * A subscriber that stalls for five keep-alive intervals while messages keep coming:
	 * its buffer fills, but no thread of the protocol may wait for it — the connection stays up
	 * and the publisher of the same instance keeps its rate.
	 */
	@Test
	void aStalledSubscriberDoesNotBlockTheConnection() throws Exception {
		assumeTrue(tck.has(FEATURE_QOS), "needs QoS 1: without broker flow control a stalled subscriber loses messages");
		int rate = 100;
		int count = (int) (rate * 8 * scale);
		Run run = new Run("stalled", 2_000_000L);
		run.stallAt(50, TimeUnit.SECONDS.toNanos(10));
		long published = run.publish(count, rate);
		run.awaitAll(count, count * 4L + 15_000);
		run.report(count, published);
		run.assertComplete(count);
		run.assertPublisherKeptItsRate(count, rate, published);
	}

	/**
	 * A stream published at full speed (#18): the send window of the instance, not the caller,
	 * keeps it within the protocol's limits — no failed publish, nothing lost.
	 */
	@Test
	void aStreamPublishedAtFullSpeedStaysWithinTheWindow() throws Exception {
		int count = (int) (3000 * scale);
		Run run = new Run("stream", 0);
		long start = System.nanoTime();
		Stream<Message> messages = IntStream.range(0, count).mapToObj(i -> {
			ByteBuffer payload = ByteBuffer.allocate(payloadSize);
			payload.putLong(0, i);
			payload.putLong(8, System.nanoTime());
			return builder().content(payload).buildMessage();
		});
		assertThat(publisher.publish(new PushStreamProvider().streamOf(messages), run.context).getValue()).isEqualTo(count);
		long published = System.nanoTime() - start;
		run.awaitAll(count, 30_000);
		run.report(count, published);
		run.assertComplete(count);
	}

	/**
	 * A subscriber slower than the sender at QoS 0, with a small latest-wins buffer (#16): no
	 * broker flow control, so the buffer discards — every message either arrives or is counted as
	 * dropped, the newest arrives, and neither the connection nor the publisher suffers.
	 */
	@Test
	void aSlowSubscriberAtQos0KeepsTheLatest() throws Exception {
		int rate = 400;
		int count = (int) (rate * 5 * scale);
		Object qos = tck.has(FEATURE_QOS) ? QOS_AT_MOST_ONCE : null;
		Run run = new Run("latest", 10_000_000L, b -> b.extensionEntry(EXTENSION_QOS, qos).extensionEntry(EXTENSION_BUFFER_SIZE, 16)
				.extensionEntry(EXTENSION_QUEUE_POLICY, "DISCARD_OLDEST"));
		long published = run.publish(count, rate);
		tck.await("received or dropped", () -> run.received.size() + run.dropped() >= count);
		run.report(count, published);
		assertThat(run.received.size() + run.dropped()).as("every message received or counted as dropped").isEqualTo(count);
		assertThat(run.dropped()).as("a subscriber at 100/s drops at 400/s").isPositive();
		assertThat(run.reconnects() - run.reconnectsBefore).isZero();
		run.assertPublisherKeptItsRate(count, rate, published);
		tck.await("the newest message", () -> {
			synchronized (run.received) {
				return !run.received.isEmpty() && run.received.get(run.received.size() - 1)[0] == count - 1;
			}
		});
	}

	/** One profile run: a subscription with a given processing time per message. */
	private final class Run {

		private final String channel;
		private final MessageContext context;
		private final List<long[]> received = Collections.synchronizedList(new ArrayList<>());
		private final List<Long> latencies = new CopyOnWriteArrayList<>();
		private final long reconnectsBefore;
		private final AtomicLong first = new AtomicLong();
		private final AtomicLong last = new AtomicLong();
		private volatile long stallAt = -1;
		private volatile long stallNanos;

		Run(String name, long processingNanos) throws Exception {
			this(name, processingNanos, b -> b.extensionEntry(EXTENSION_QOS, tck.has(FEATURE_QOS) ? QOS_AT_LEAST_ONCE : null));
		}

		Run(String name, long processingNanos, UnaryOperator<MessageContextBuilder> options) throws Exception {
			this.channel = tck.channel("load", name);
			this.context = options.apply(builder().channel(channel)).buildContext();
			this.reconnectsBefore = reconnects();
			track(subscription.subscribe(context)).forEach(m -> {
				ByteBuffer b = m.payload();
				long seq = b.getLong(0);
				long sent = b.getLong(8);
				long now = System.nanoTime();
				first.compareAndSet(0, now);
				latencies.add(now - sent);
				long pause = processingNanos + (seq == stallAt ? stallNanos : 0);
				if (pause > 0) {
					long until = now + pause;
					while (System.nanoTime() < until) {
						LockSupport.parkNanos(Math.max(1, until - System.nanoTime()));
					}
				}
				received.add(new long[] { seq });
				last.set(System.nanoTime());
			});
			// a subscription is made on the broker asynchronously on some protocols
			tck.settle();
		}

		/** The subscriber stalls once, at a message. */
		void stallAt(long seq, long nanos) {
			stallNanos = nanos;
			stallAt = seq;
		}

		/** A subscriber, slow or stalled, must not hold back the publisher of the instance. */
		void assertPublisherKeptItsRate(int count, int rate, long published) {
			long nominal = count * 1_000_000_000L / rate;
			assertThat(ms(published)).as("publishing %d messages at %d/s (ms): the subscriber held the publisher back", count, rate)
					.isLessThan(ms(nominal + nominal / 5));
		}

		/**
		 * Publishes count messages, at most {@link #window} outstanding.
		 *
		 * @param rate messages per second, 0 for full speed
		 * @return the time the publishing took, ns
		 */
		long publish(int count, int rate) throws Exception {
			Semaphore outstanding = new Semaphore(window);
			List<Promise<Void>> failures = new CopyOnWriteArrayList<>();
			long start = System.nanoTime();
			for (int i = 0; i < count; i++) {
				if (rate > 0) {
					long due = start + i * 1_000_000_000L / rate;
					long wait = due - System.nanoTime();
					if (wait > 0) {
						TimeUnit.NANOSECONDS.sleep(wait);
					}
				}
				ByteBuffer payload = ByteBuffer.allocate(payloadSize);
				payload.putLong(0, i);
				payload.putLong(8, System.nanoTime());
				outstanding.acquire();
				Promise<Void> p = publisher.publish(builder().content(payload).buildMessage(), context);
				p.onResolve(outstanding::release);
				p.onFailure(f -> failures.add(p));
			}
			outstanding.acquire(window);
			long took = System.nanoTime() - start;
			assertThat(failures).as("failed publishes").isEmpty();
			return took;
		}

		void awaitAll(int count, long millis) throws InterruptedException {
			long end = System.currentTimeMillis() + millis;
			while (received.size() < count && System.currentTimeMillis() < end) {
				Thread.sleep(20);
			}
		}

		/** @return from the first message received to the last one processed, ns */
		long consumed() {
			return last.get() - first.get();
		}

		void report(int count, long published) throws Exception {
			List<Long> sorted = new ArrayList<>(latencies);
			Collections.sort(sorted);
			long reconnects = reconnects() - reconnectsBefore;
			String line = String.format(
					"%s %s: %d/%d received, published in %d ms (%.0f/s), consumed in %d ms (%.0f/s), latency p50 %d ms, p99 %d ms, max %d ms, reconnects %d",
					tck.filter(), profile, received.size(), count, ms(published), count * 1e9 / Math.max(1, published),
					ms(consumed()), received.size() * 1e9 / Math.max(1, consumed()), ms(percentile(sorted, 50)),
					ms(percentile(sorted, 99)), ms(sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1)), reconnects);
			System.out.println("LOAD " + line);
			reporter.publishEntry("load", line);
		}

		void assertComplete(int count) throws Exception {
			assertThat(reconnects() - reconnectsBefore).as("reconnects: a blocked protocol thread loses the connection")
					.isZero();
			List<Long> seqs = new ArrayList<>();
			synchronized (received) {
				received.forEach(r -> seqs.add(r[0]));
			}
			assertThat(seqs).as("every message once, in order").hasSize(count);
			for (int i = 0; i < seqs.size(); i++) {
				if (seqs.get(i) != i) {
					throw new AssertionError("out of order or missing at " + i + ": " + seqs.get(i));
				}
			}
		}

		/** @return the messages the subscription's buffer dropped */
		long dropped() {
			for (SubscriptionDTO s : tck.service(MessageServiceRuntime.class).getRuntimeDTO().subscriptions) {
				if (channel.equals(s.channel.name)) {
					return s.droppedMessages;
				}
			}
			return 0;
		}

		private long reconnects() {
			return tck.service(MessageServiceRuntime.class).getRuntimeDTO().reconnects;
		}

	}

	private static long ms(long nanos) {
		return TimeUnit.NANOSECONDS.toMillis(nanos);
	}

	private static long percentile(List<Long> sorted, int p) {
		if (sorted.isEmpty()) {
			return 0;
		}
		return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
	}

}
