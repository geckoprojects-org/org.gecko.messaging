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
package org.gecko.messaging.api.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.osgi.util.pushstream.PushEventConsumer;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamProvider;

class FlowsTest {

	private final PushStreamProvider streams = new PushStreamProvider();

	/** Records what a publisher's subscriber receives. */
	private static final class Recorder implements Flow.Subscriber<Integer> {

		final List<Integer> items = new CopyOnWriteArrayList<>();
		final AtomicReference<Throwable> failure = new AtomicReference<>();
		final CountDownLatch ended = new CountDownLatch(1);
		volatile Flow.Subscription subscription;
		final long initial;

		Recorder(long initial) {
			this.initial = initial;
		}

		@Override
		public void onSubscribe(Flow.Subscription s) {
			subscription = s;
			if (initial > 0) {
				s.request(initial);
			}
		}

		@Override
		public void onNext(Integer item) {
			items.add(item);
		}

		@Override
		public void onError(Throwable t) {
			failure.set(t);
			ended.countDown();
		}

		@Override
		public void onComplete() {
			ended.countDown();
		}

	}

	/** A publisher driven by the test, recording requests and cancel. */
	private static final class Driven implements Flow.Publisher<Integer> {

		final AtomicLong requested = new AtomicLong();
		final AtomicBoolean cancelled = new AtomicBoolean();
		final CountDownLatch subscribed = new CountDownLatch(1);
		volatile Flow.Subscriber<? super Integer> subscriber;

		@Override
		public void subscribe(Flow.Subscriber<? super Integer> s) {
			subscriber = s;
			s.onSubscribe(new Flow.Subscription() {
				@Override
				public void request(long n) {
					requested.addAndGet(n);
				}

				@Override
				public void cancel() {
					cancelled.set(true);
				}
			});
			subscribed.countDown();
		}

	}

	private PushStream<Integer> range(int n) {
		return streams.streamOf(IntStream.range(0, n).boxed());
	}

	private static void await(String what, BooleanSupplier condition) throws InterruptedException {
		long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean()) {
			assertThat(System.nanoTime()).as(what).isLessThan(end);
			Thread.sleep(10);
		}
	}

	@Test
	void toPublisherDeliversNoMoreThanAsked() throws Exception {
		Recorder r = new Recorder(3);
		Flows.toPublisher(range(10)).subscribe(r);
		await("three items", () -> r.items.size() == 3);
		Thread.sleep(100);
		assertThat(r.items).as("held back without demand").containsExactly(0, 1, 2);
		r.subscription.request(Long.MAX_VALUE);
		assertThat(r.ended.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(r.items).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
		assertThat(r.failure.get()).isNull();
	}

	@Test
	void cancellingTheSubscriptionClosesTheStream() throws Exception {
		CountDownLatch closed = new CountDownLatch(1);
		Recorder r = new Recorder(1);
		Flows.toPublisher(range(100).onClose(closed::countDown)).subscribe(r);
		await("one item", () -> r.items.size() == 1);
		r.subscription.cancel();
		assertThat(closed.await(5, TimeUnit.SECONDS)).as("the stream closed").isTrue();
		assertThat(r.items).containsExactly(0);
	}

	@Test
	void theFailureOfTheStreamFailsTheSubscriber() throws Exception {
		Recorder r = new Recorder(Long.MAX_VALUE);
		Flows.toPublisher(range(5).map(i -> {
			if (i == 2) {
				throw new IllegalStateException("broken");
			}
			return i;
		})).subscribe(r);
		assertThat(r.ended.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(r.items).containsExactly(0, 1);
		assertThat(r.failure.get()).hasMessage("broken");
	}

	@Test
	void aStreamTakesOneSubscriber() throws Exception {
		Flow.Publisher<Integer> p = Flows.toPublisher(range(1));
		p.subscribe(new Recorder(1));
		Recorder second = new Recorder(1);
		p.subscribe(second);
		assertThat(second.ended.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(second.failure.get()).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aNonPositiveRequestFailsAndCancels() throws Exception {
		CountDownLatch closed = new CountDownLatch(1);
		Recorder r = new Recorder(0);
		Flows.toPublisher(range(5).onClose(closed::countDown)).subscribe(r);
		r.subscription.request(0);
		assertThat(r.ended.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(r.failure.get()).isInstanceOf(IllegalArgumentException.class);
		assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void fromPublisherRequestsOneAtATime() throws Exception {
		Driven p = new Driven();
		List<Integer> taken = new CopyOnWriteArrayList<>();
		Flows.fromPublisher(p).forEach(taken::add);
		assertThat(p.subscribed.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(p.requested.get()).as("one at first").isEqualTo(1);
		for (int i = 0; i < 5; i++) {
			p.subscriber.onNext(i);
			assertThat(p.requested.get()).as("the next when the previous was taken").isEqualTo(i + 2);
		}
		assertThat(taken).containsExactly(0, 1, 2, 3, 4);
	}

	@Test
	void aPositiveBackPressureDelaysTheNextRequest() throws Exception {
		Driven p = new Driven();
		Flows.fromPublisher(p).forEachEvent(e -> e.isTerminal() ? PushEventConsumer.ABORT : 200);
		assertThat(p.subscribed.await(5, TimeUnit.SECONDS)).isTrue();
		p.subscriber.onNext(0);
		Thread.sleep(50);
		assertThat(p.requested.get()).as("not yet").isEqualTo(1);
		await("requested after the back-pressure", () -> p.requested.get() == 2);
	}

	@Test
	void closingTheStreamCancelsThePublisher() throws Exception {
		Driven p = new Driven();
		PushStream<Integer> stream = Flows.fromPublisher(p);
		stream.forEach(i -> {
			// taken
		});
		assertThat(p.subscribed.await(5, TimeUnit.SECONDS)).isTrue();
		p.subscriber.onNext(0);
		stream.close();
		assertThat(p.cancelled.get()).as("the source learned the end").isTrue();
	}

	@Test
	void anErrorAndACompletionTravelIntoTheStream() throws Exception {
		Driven failing = new Driven();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch failed = new CountDownLatch(1);
		Flows.fromPublisher(failing).onError(t -> {
			failure.set(t);
			failed.countDown();
		}).forEach(i -> {
			// taken
		});
		failing.subscribed.await(5, TimeUnit.SECONDS);
		failing.subscriber.onError(new IllegalStateException("source failed"));
		assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(failure.get()).hasMessage("source failed");

		Driven completing = new Driven();
		List<Integer> taken = new CopyOnWriteArrayList<>();
		CountDownLatch closed = new CountDownLatch(1);
		Flows.fromPublisher(completing).onClose(closed::countDown).forEach(taken::add);
		completing.subscribed.await(5, TimeUnit.SECONDS);
		completing.subscriber.onNext(7);
		completing.subscriber.onComplete();
		assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(taken).containsExactly(7);
	}

}
