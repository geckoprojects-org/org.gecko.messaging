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

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushEventConsumer;
import org.osgi.util.pushstream.PushEventSource;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamProvider;

/**
 * Adapters between {@link PushStream}, which the messaging API speaks, and
 * {@link java.util.concurrent.Flow.Publisher}, the JDK type every reactive library adapts to.
 * <p>
 * <b>{@link #toPublisher(PushStream)}</b>: the stream pushes and the subscriber asks. The stream
 * is consumed on a thread of its own (a virtual thread, or one of a given executor) that waits
 * while the subscriber asked for nothing, which holds the stream back. A stream that is
 * buffered keeps receiving into its buffer meanwhile, as its queue policy says. Cancelling the
 * subscription closes the stream; the stream's end completes the subscriber, its failure fails
 * it. A stream can be consumed once: the publisher takes one subscriber.
 * <p>
 * <b>{@link #fromPublisher(Flow.Publisher)}</b>: the stream requests one element at a time, the
 * next when its consumer took the previous one. A positive back-pressure of the consumer delays
 * the next request by as many milliseconds; a negative one cancels the subscription. Closing the
 * stream cancels the subscription, so that the end of a session or of a stream publish reaches
 * the application's source. The publisher's completion closes the stream, its error fails it.
 * The stream is unbuffered: it runs on the publisher's threads and starts no threads of its own.
 * The publisher is subscribed when the stream is connected, once per connection.
 */
public final class Flows {

	private static final PushStreamProvider PROVIDER = new PushStreamProvider();

	private Flows() {
		// utilities only
	}

	/**
	 * Adapts a stream to a publisher, consumed on a virtual thread.
	 *
	 * @param <T> the element type
	 * @param stream the stream, not connected yet
	 * @return a publisher for one subscriber
	 */
	public static <T> Flow.Publisher<T> toPublisher(PushStream<T> stream) {
		return toPublisher(stream, r -> Thread.ofVirtual().name("flows-publisher").start(r));
	}

	/**
	 * Adapts a stream to a publisher.
	 *
	 * @param <T> the element type
	 * @param stream the stream, not connected yet
	 * @param executor runs the consumption of the stream: it waits while there is no demand, so
	 *            it occupies a thread of the executor for the lifetime of the subscription
	 * @return a publisher for one subscriber
	 */
	public static <T> Flow.Publisher<T> toPublisher(PushStream<T> stream, Executor executor) {
		Objects.requireNonNull(stream, "stream");
		Objects.requireNonNull(executor, "executor");
		AtomicBoolean subscribed = new AtomicBoolean();
		return subscriber -> {
			Objects.requireNonNull(subscriber, "subscriber");
			if (!subscribed.compareAndSet(false, true)) {
				subscriber.onSubscribe(new Flow.Subscription() {
					@Override
					public void request(long n) {
						// nothing to deliver
					}

					@Override
					public void cancel() {
						// nothing to cancel
					}
				});
				subscriber.onError(new IllegalStateException("A stream can be consumed once: the publisher takes one subscriber"));
				return;
			}
			StreamSubscription<T> subscription = new StreamSubscription<>(stream, subscriber);
			subscriber.onSubscribe(subscription);
			executor.execute(subscription::consume);
		};
	}

	/**
	 * Adapts a publisher to an unbuffered stream.
	 *
	 * @param <T> the element type
	 * @param publisher the publisher
	 * @return the stream; each connection subscribes the publisher
	 */
	public static <T> PushStream<T> fromPublisher(Flow.Publisher<T> publisher) {
		return fromPublisher(PROVIDER, publisher);
	}

	/**
	 * Adapts a publisher to an unbuffered stream of the given provider.
	 *
	 * @param <T> the element type
	 * @param provider the provider of the stream
	 * @param publisher the publisher
	 * @return the stream; each connection subscribes the publisher
	 */
	public static <T> PushStream<T> fromPublisher(PushStreamProvider provider, Flow.Publisher<T> publisher) {
		Objects.requireNonNull(provider, "provider");
		Objects.requireNonNull(publisher, "publisher");
		PushEventSource<T> source = consumer -> {
			PublisherSubscriber<T> s = new PublisherSubscriber<>(consumer);
			publisher.subscribe(s);
			return s::close;
		};
		return provider.buildStream(source).unbuffered().build();
	}

	/** The subscription of {@link #toPublisher}: consumes the stream as far as asked. */
	private static final class StreamSubscription<T> implements Flow.Subscription {

		private final PushStream<T> stream;
		private final Flow.Subscriber<? super T> subscriber;
		private final AtomicBoolean done = new AtomicBoolean();
		private long demand;
		private boolean cancelled;

		StreamSubscription(PushStream<T> stream, Flow.Subscriber<? super T> subscriber) {
			this.stream = stream;
			this.subscriber = subscriber;
		}

		void consume() {
			try {
				stream.forEachEvent(this::accept);
			} catch (RuntimeException e) {
				// e.g. a stream that was consumed already
				terminate(e);
			}
		}

		private long accept(PushEvent<? extends T> event) {
			switch (event.getType()) {
				case DATA:
					synchronized (this) {
						try {
							while (demand == 0 && !cancelled) {
								wait();
							}
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							cancelled = true;
						}
						if (cancelled) {
							return PushEventConsumer.ABORT;
						}
						if (demand != Long.MAX_VALUE) {
							demand--;
						}
					}
					try {
						subscriber.onNext(event.getData());
					} catch (RuntimeException e) {
						// a subscriber must not throw (Reactive Streams 2.13): treat it as cancelled
						cancel();
						return PushEventConsumer.ABORT;
					}
					return isCancelled() ? PushEventConsumer.ABORT : PushEventConsumer.CONTINUE;
				case ERROR:
					terminate(event.getFailure());
					return PushEventConsumer.ABORT;
				default:
					terminate(null);
					return PushEventConsumer.ABORT;
			}
		}

		private synchronized boolean isCancelled() {
			return cancelled;
		}

		private void terminate(Throwable failure) {
			if (isCancelled() || !done.compareAndSet(false, true)) {
				return;
			}
			if (failure == null) {
				subscriber.onComplete();
			} else {
				subscriber.onError(failure);
			}
		}

		@Override
		public void request(long n) {
			if (n <= 0) {
				// Reactive Streams 3.9
				boolean first;
				synchronized (this) {
					first = !cancelled;
				}
				if (first && done.compareAndSet(false, true)) {
					subscriber.onError(new IllegalArgumentException("request must be positive, was " + n));
				}
				cancel();
				return;
			}
			synchronized (this) {
				long d = demand + n;
				demand = d < 0 ? Long.MAX_VALUE : d;
				notifyAll();
			}
		}

		@Override
		public void cancel() {
			synchronized (this) {
				if (cancelled) {
					return;
				}
				cancelled = true;
				notifyAll();
			}
			stream.close();
		}

	}

	/** The subscriber of {@link #fromPublisher}: one element at a time into the stream. */
	private static final class PublisherSubscriber<T> implements Flow.Subscriber<T> {

		private final PushEventConsumer<? super T> consumer;
		private final AtomicBoolean closed = new AtomicBoolean();
		private volatile Flow.Subscription subscription;

		PublisherSubscriber(PushEventConsumer<? super T> consumer) {
			this.consumer = consumer;
		}

		@Override
		public void onSubscribe(Flow.Subscription s) {
			Objects.requireNonNull(s, "subscription");
			if (subscription != null || closed.get()) {
				// Reactive Streams 2.5: a second subscription, or one for a closed stream
				s.cancel();
				return;
			}
			subscription = s;
			s.request(1);
		}

		@Override
		public void onNext(T item) {
			if (closed.get()) {
				return;
			}
			long backPressure;
			try {
				backPressure = consumer.accept(PushEvent.data(item));
			} catch (Exception e) {
				fail(e);
				return;
			}
			Flow.Subscription s = subscription;
			if (backPressure < 0) {
				close();
			} else if (backPressure == 0) {
				s.request(1);
			} else {
				CompletableFuture.delayedExecutor(backPressure, TimeUnit.MILLISECONDS).execute(() -> {
					if (!closed.get()) {
						s.request(1);
					}
				});
			}
		}

		@Override
		public void onError(Throwable failure) {
			fail(failure);
		}

		@Override
		public void onComplete() {
			if (closed.compareAndSet(false, true)) {
				deliver(PushEvent.close());
			}
		}

		private void fail(Throwable failure) {
			if (closed.compareAndSet(false, true)) {
				Flow.Subscription s = subscription;
				if (s != null) {
					s.cancel();
				}
				deliver(PushEvent.error(failure));
			}
		}

		private void deliver(PushEvent<T> event) {
			try {
				consumer.accept(event);
			} catch (Exception e) {
				// the stream ended
			}
		}

		/** The stream closed: the source learns it. */
		void close() {
			if (closed.compareAndSet(false, true)) {
				Flow.Subscription s = subscription;
				if (s != null) {
					s.cancel();
				}
			}
		}

	}

}
