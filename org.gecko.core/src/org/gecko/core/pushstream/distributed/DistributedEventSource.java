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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.gecko.core.pushstream.PushStreamContext;
import org.gecko.core.pushstream.PushStreamHelper;
import org.osgi.util.pushstream.PushEventConsumer;
import org.osgi.util.pushstream.PushEventSource;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamProvider;
import org.osgi.util.pushstream.SimplePushEventSource;

/**
 * 
 * @author mark
 * @since 08.03.2019
 */
public class DistributedEventSource<T> implements PushEventSource<T> {
	
	private static final Logger logger = Logger.getLogger(DistributedEventSource.class.getName()); 
	private final PushStreamProvider psp = new PushStreamProvider();
	private final AtomicReference<Runnable> connectFunction = new AtomicReference<Runnable>();
	private final AtomicReference<Runnable> closeFunction = new AtomicReference<>();
	private final AtomicReference<Consumer<Throwable>> errorFunction = new AtomicReference<>();
	private final AtomicReference<Throwable> error = new AtomicReference<>();
	private final AtomicBoolean close = new AtomicBoolean(false);
	protected final SimplePushEventSource<T> eventSource;
	
	/**
	 * Creates a new instance.
	 */
	public DistributedEventSource(SimplePushEventSource<T> source) {
		eventSource = source;
		eventSource.connectPromise().onResolve(this::doOnConnect);
	}
	
	/**
	 * Creates a new instance.
	 */
	public DistributedEventSource(Class<T> sourceClass) {
		eventSource = psp.createSimpleEventSource(sourceClass);
		eventSource.connectPromise().onResolve(this::doOnConnect);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushEventSource#open(org.osgi.util.pushstream.PushEventConsumer)
	 */
	@Override
	public AutoCloseable open(PushEventConsumer<? super T> aec) throws Exception {
		return eventSource.open(aec);
	}
	
	/**
	 * Publishes an error to the event source. It sets the inner state to error. 
	 * So no {@link DistributedEventSource#onError(Consumer)} will not be called!
	 * @param t the error to publish
	 */
	public void doExternalError(Throwable t) {
		if (error.compareAndSet(null, t)) {
			close.set(true);
			eventSource.error(t);
		}
	}
	
	/**
	 * Publishes a close to the event source. It sets the inner state to close.
	 * So no {@link DistributedEventSource#onClose(Runnable)} will not be called!
	 */
	public void doExternalClose() {
		if (close.compareAndSet(false, true)) {
			eventSource.endOfStream();
		}
	}
	
	/**
	 * Publishes data to the event source.
	 * @param o the object to publish
	 */
	public void doExternalPublish(T o) {
		if (!eventSource.isConnected()) {
			logger.severe("The underlaying event source is not connected. This should not happen. Data gets lost!");
		} else {
			eventSource.publish(o);
		}
	}
	
	/**
	 * Sets a {@link Runnable} that is called when a terminal operation is called and the event source connects
	 * @param connectHandler the connect handler to be called
	 * @return the {@link PushStream} instance
	 */
	public DistributedEventSource<T> onConnect(Runnable connectHandler) {
		if(!connectFunction.compareAndSet(null, connectHandler)) {
			throw new IllegalStateException("A connect handler has already been defined for this source object");
		}
		return this;
	}

	/**
	 * Registers an on close handler, that is called, when the underlying {@link PushStream} is closed 
	 * @param closeHandler the handler to register
	 * @return the instance
	 */
	public DistributedEventSource<T> onClose(Runnable closeHandler) {
		if (!closeFunction.compareAndSet(null, closeHandler)) {
			throw new IllegalStateException("A close handler has already been set");
		}
		return this;
	}
	
	/**
	 * Registers an on error handler, that is called, when the underlying {@link PushStream} caused an error.
	 * @param closeHandler the handler to register
	 * @return the instance
	 */
	public DistributedEventSource<T> onError(Consumer<Throwable> errorHandler) {
		if (!errorFunction.compareAndSet(null, errorHandler)) {
			throw new IllegalStateException("An error handler has already been set");
		}
		return this;
	}
	
	/**
	 * Creates a {@link DistributedPushStream} out of this event source
	 * @param context the context to configure the {@link PushStream}, can be <code>null</code>;
	 * @return the {@link DistributedPushStream} instance
	 */
	public DistributedPushStream<T> createPushStream(PushStreamContext<T> context) {
		PushStream<T> delegate = PushStreamHelper.createPushStream(this, context);
		DistributedPushStream<T> dps = new DistributedPushStream<T>(delegate);
		dps.distributeOnClose(this::doOnClose).distributeOnError(this::doOnError);
		return dps;
	}
	
	/**
	 * Gets called, when the underlying {@link PushStream} caused an error.
	 * If a handler is set is will only called, if the error was caused from the {@link PushStream}
	 * pipeline and not from the {@link DistributedEventSource#doExternalError} method
	 */
	private synchronized void doOnError(Throwable t) {
		if (error.compareAndSet(t, null)) {
			return;
		}
		Consumer<Throwable> errorHandler = errorFunction.get();
		if (errorHandler != null) {
			errorHandler.accept(t);
		}
	}
	
	/**
	 * Gets called, when the underlying {@link PushStream} was closed
	 */
	private void doOnClose() {
		if (close.compareAndSet(true, false)) {
			return;
		}
		Runnable closeHandler = closeFunction.get();
		if (closeHandler != null) {
			closeHandler.run();
		}
	}
	
	/**
	 * Executes the distConnectHandler, if it is set
	 * @return the {@link PushStream} instance
	 */
	private void doOnConnect() {
		Runnable distOnConnect = connectFunction.getAndSet(null);
		if (distOnConnect != null) {
			distOnConnect.run();
		}
	}

}
