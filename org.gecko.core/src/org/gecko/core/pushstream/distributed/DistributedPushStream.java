/**
 * Copyright (c) 2012 - 2019 Data In Motion and others.
 * All rights reserved. 
 * 
 * This program and the accompanying materials are made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
package org.gecko.core.pushstream.distributed;

import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.LongUnaryOperator;
import java.util.function.Supplier;
import java.util.function.ToLongBiFunction;
import java.util.stream.Collector;

import org.osgi.util.function.Function;
import org.osgi.util.function.Predicate;
import org.osgi.util.promise.Promise;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushEventConsumer;
import org.osgi.util.pushstream.PushEventSource;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamBuilder;

/**
 * Distributed {@link PushStream}. 
 * It delegates external commands for close, error and connect to the containing {@link PushStream}. 
 * In the other direction it delegates accept, error and close events to additional callbacks.
 *  
 * @author Mark Hoffmann
 * @since 05.03.2019
 */
public class DistributedPushStream<T> implements PushStream<T>{
	
	private final PushStream<T> delegate;
	protected DistributedOnErrorCallback distErrorConsumer = DistributedOnErrorCallback.create();
	protected DistributedOnCloseCallback distCloseRunnable = DistributedOnCloseCallback.create();
	
	
	public DistributedPushStream(PushStream<T> source) {
		delegate = source;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#close()
	 */
	@Override
	public void close() {
		delegate.close();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#onClose(java.lang.Runnable)
	 */
	@Override
	public PushStream<T> onClose(Runnable closeHandler) {
		distCloseRunnable.withOnClose(closeHandler);
		return this;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#onError(java.util.function.Consumer)
	 */
	@Override
	public PushStream<T> onError(Consumer<? super Throwable> errorHandler) {
		distErrorConsumer.withOnError(errorHandler);
		return this;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#filter(org.osgi.util.function.Predicate)
	 */
	@Override
	public PushStream<T> filter(Predicate<? super T> predicate) {
		return delegate.filter(predicate);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#map(org.osgi.util.function.Function)
	 */
	@Override
	public <R> PushStream<R> map(Function<? super T, ? extends R> mapper) {
		return delegate.map(mapper);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#asyncMap(int, int, org.osgi.util.function.Function)
	 */
	@Override
	public <R> PushStream<R> asyncMap(int n, int delay, Function<? super T, Promise<? extends R>> mapper) {
		return delegate.asyncMap(n, delay, mapper);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#flatMap(org.osgi.util.function.Function)
	 */
	@Override
	public <R> PushStream<R> flatMap(Function<? super T, ? extends PushStream<? extends R>> mapper) {
		return delegate.flatMap(mapper);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#distinct()
	 */
	@Override
	public PushStream<T> distinct() {
		return delegate.distinct();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#sorted()
	 */
	@Override
	public PushStream<T> sorted() {
		return delegate.sorted();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#sorted(java.util.Comparator)
	 */
	@Override
	public PushStream<T> sorted(Comparator<? super T> comparator) {
		return delegate.sorted(comparator);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#limit(long)
	 */
	@Override
	public PushStream<T> limit(long maxSize) {
		return delegate.limit(maxSize);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#limit(java.time.Duration)
	 */
	@Override
	public PushStream<T> limit(Duration maxTime) {
		return delegate.limit(maxTime);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#timeout(java.time.Duration)
	 */
	@Override
	public PushStream<T> timeout(Duration idleTime) {
		return delegate.timeout(idleTime);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#skip(long)
	 */
	@Override
	public PushStream<T> skip(long n) {
		return delegate.skip(n);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#fork(int, int, java.util.concurrent.Executor)
	 */
	@Override
	public PushStream<T> fork(int n, int delay, Executor e) {
		return delegate.fork(n, delay, e);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#buffer()
	 */
	@Override
	public PushStream<T> buffer() {
		return delegate.buffer();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#buildBuffer()
	 */
	@Override
	public <U extends BlockingQueue<PushEvent<? extends T>>> PushStreamBuilder<T, U> buildBuffer() {
		return delegate.buildBuffer();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#merge(org.osgi.util.pushstream.PushEventSource)
	 */
	@Override
	public PushStream<T> merge(PushEventSource<? extends T> source) {
		return delegate.merge(source);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#merge(org.osgi.util.pushstream.PushStream)
	 */
	@Override
	public PushStream<T> merge(PushStream<? extends T> source) {
		return delegate.merge(delegate);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#split(org.osgi.util.function.Predicate[])
	 */
	@SuppressWarnings("unchecked")
	@Override
	public PushStream<T>[] split(Predicate<? super T>... predicates) {
		return delegate.split(predicates);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#sequential()
	 */
	@Override
	public PushStream<T> sequential() {
		return delegate.sequential();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#coalesce(org.osgi.util.function.Function)
	 */
	@Override
	public <R> PushStream<R> coalesce(Function<? super T, Optional<R>> f) {
		return delegate.coalesce(f);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#coalesce(int, org.osgi.util.function.Function)
	 */
	@Override
	public <R> PushStream<R> coalesce(int count, Function<Collection<T>, R> f) {
		return delegate.coalesce(count, f);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#coalesce(java.util.function.IntSupplier, org.osgi.util.function.Function)
	 */
	@Override
	public <R> PushStream<R> coalesce(IntSupplier count, Function<Collection<T>, R> f) {
		return delegate.coalesce(count, f);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#window(java.time.Duration, org.osgi.util.function.Function)
	 */
	@Override
	public <R> PushStream<R> window(Duration d, Function<Collection<T>, R> f) {
		return delegate.window(d, f);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#window(java.time.Duration, java.util.concurrent.Executor, org.osgi.util.function.Function)
	 */
	@Override
	public <R> PushStream<R> window(Duration d, Executor executor, Function<Collection<T>, R> f) {
		return delegate.window(d, executor, f);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#window(java.util.function.Supplier, java.util.function.IntSupplier, java.util.function.BiFunction)
	 */
	@Override
	public <R> PushStream<R> window(Supplier<Duration> timeSupplier, IntSupplier maxEvents,
			BiFunction<Long, Collection<T>, R> f) {
		return delegate.window(timeSupplier, maxEvents, f);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#window(java.util.function.Supplier, java.util.function.IntSupplier, java.util.concurrent.Executor, java.util.function.BiFunction)
	 */
	@Override
	public <R> PushStream<R> window(Supplier<Duration> timeSupplier, IntSupplier maxEvents, Executor executor,
			BiFunction<Long, Collection<T>, R> f) {
		return delegate.window(timeSupplier, maxEvents, executor, f);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#adjustBackPressure(java.util.function.LongUnaryOperator)
	 */
	@Override
	public PushStream<T> adjustBackPressure(LongUnaryOperator adjustment) {
		return delegate.adjustBackPressure(adjustment);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#adjustBackPressure(java.util.function.ToLongBiFunction)
	 */
	@Override
	public PushStream<T> adjustBackPressure(ToLongBiFunction<T, Long> adjustment) {
		return delegate.adjustBackPressure(adjustment);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#forEach(java.util.function.Consumer)
	 */
	@Override
	public Promise<Void> forEach(Consumer<? super T> action) {
		return delegate.forEach(action);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#toArray()
	 */
	@Override
	public Promise<Object[]> toArray() {
		return delegate.toArray();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#reduce(java.lang.Object, java.util.function.BinaryOperator)
	 */
	@Override
	public Promise<T> reduce(T identity, BinaryOperator<T> accumulator) {
		return delegate.reduce(identity, accumulator);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#reduce(java.util.function.BinaryOperator)
	 */
	@Override
	public Promise<Optional<T>> reduce(BinaryOperator<T> accumulator) {
		return delegate.reduce(accumulator);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#reduce(java.lang.Object, java.util.function.BiFunction, java.util.function.BinaryOperator)
	 */
	@Override
	public <U> Promise<U> reduce(U identity, BiFunction<U, ? super T, U> accumulator, BinaryOperator<U> combiner) {
		return delegate.reduce(identity, accumulator, combiner);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#collect(java.util.stream.Collector)
	 */
	@Override
	public <R, A> Promise<R> collect(Collector<? super T, A, R> collector) {
		return delegate.collect(collector);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#min(java.util.Comparator)
	 */
	@Override
	public Promise<Optional<T>> min(Comparator<? super T> comparator) {
		return delegate.min(comparator);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#max(java.util.Comparator)
	 */
	@Override
	public Promise<Optional<T>> max(Comparator<? super T> comparator) {
		return delegate.max(comparator);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#count()
	 */
	@Override
	public Promise<Long> count() {
		return delegate.count();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#anyMatch(org.osgi.util.function.Predicate)
	 */
	@Override
	public Promise<Boolean> anyMatch(Predicate<? super T> predicate) {
		return delegate.anyMatch(predicate);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#allMatch(org.osgi.util.function.Predicate)
	 */
	@Override
	public Promise<Boolean> allMatch(Predicate<? super T> predicate) {
		return delegate.allMatch(predicate);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#noneMatch(org.osgi.util.function.Predicate)
	 */
	@Override
	public Promise<Boolean> noneMatch(Predicate<? super T> predicate) {
		return delegate.noneMatch(predicate);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#findFirst()
	 */
	@Override
	public Promise<Optional<T>> findFirst() {
		return delegate.findFirst();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#findAny()
	 */
	@Override
	public Promise<Optional<T>> findAny() {
		return delegate.findAny();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#forEachEvent(org.osgi.util.pushstream.PushEventConsumer)
	 */
	@Override
	public Promise<Long> forEachEvent(PushEventConsumer<? super T> action) {
		return delegate.forEachEvent(action);
	}
	
	/**
	 * Sets a {@link Runnable} that is called in addition to the original close handler.
	 * @param closeHandler the additional handler to be called
	 * @return the {@link DistributedPushStream} instance
	 */
	public DistributedPushStream<T> distributeOnClose(Runnable closeHandler) {
		distCloseRunnable.withDistributedOnClose(closeHandler);
		delegate.onClose(distCloseRunnable);
		return this;
	}
	
	/**
	 * Sets a {@link Consumer} that is called in addition to the original close handler.
	 * @param closeError the additional handler to be called
	 * @return the {@link PushStream} instance
	 */
	public DistributedPushStream<T> distributeOnError(Consumer<? super Throwable> closeError) {
		distErrorConsumer.withDistributedOnError(closeError);
		delegate.onError(distErrorConsumer);
		return this;
	}
	
	/**
	 * Returns the underlying {@link PushStream} instance
	 * @return the underlying {@link PushStream} instance
	 */
	public PushStream<T> getDelegate() {
		return delegate;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushStream#toArray(java.util.function.IntFunction)
	 */
	@Override
	public <A> Promise<A[]> toArray(IntFunction<A[]> generator) {
		return delegate.toArray(generator);
	}
	
}
