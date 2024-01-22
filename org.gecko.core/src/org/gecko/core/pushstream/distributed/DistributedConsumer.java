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

import org.osgi.util.pushstream.PushStream;

/**
 * Distributed event consumer. 
 * It delegates external commands for close, error and connect to the containing {@link PushStream}. 
 * In the other direction it delegates accept, error and close events to additional callbacks.
 *  
 * @author Mark Hoffmann
 * @since 05.03.2019
 */
public class DistributedConsumer<T> {
	
	private final PushStream<T> stream;
	private final AtomicReference<Runnable> closeFunction = new AtomicReference<>();
	private final AtomicReference<Consumer<Throwable>> errorFunction = new AtomicReference<>();
	private final AtomicReference<Consumer<T>> acceptFunction = new AtomicReference<>();
	private final AtomicReference<Throwable> error = new AtomicReference<>();
	private final AtomicBoolean close = new AtomicBoolean(false);
	private volatile boolean connected = false;
	
	/**
	 * Creates a new instance.
	 */
	public DistributedConsumer(PushStream<T> sourceStream) {
		stream = sourceStream;
	}
	
	/**
	 * Registers an on close handler, that is called, when the underlying {@link PushStream} is closed 
	 * @param closeHandler the handler to register
	 * @return the instance
	 */
	public DistributedConsumer<T> onClose(Runnable closeHandler) {
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
	public DistributedConsumer<T> onError(Consumer<Throwable> errorHandler) {
		if (!errorFunction.compareAndSet(null, errorHandler)) {
			throw new IllegalStateException("An error handler has already been set");
		}
		return this;
	}
	
	public DistributedConsumer<T> onAccept(Consumer<T> acceptHandler) {
		if (!acceptFunction.compareAndSet(null, acceptHandler)) {
			throw new IllegalStateException("An accept handler has already been set");
		}
		return this;
	}
	
	/**
	 * Connects the an distributed consumer to the given {@link PushStream}. 
	 */
	public void doConnect() {
		if (connected) {
			throw new IllegalStateException("The consumer is already connected to the stream");
		}
		stream.forEachEvent(event -> {
			if (!connected) {
				return -1;
			}
			checkExternalError();
			switch (event.getType()) {
				case CLOSE:
					handleClose();
					break;
				case ERROR:
					handleError(event.getFailure());
					break;
				default:
					handleAccept(event.getData());
					break;
			}
			return 0;
		});
		connected = true;
	}
	
	/**
	 * Sets an external error to the stream, that will be thrown in the stream during consumption.
	 * This takes only effect, if {@link DistributedConsumer#doConnect} was called before. 
	 * The {@link DistributedConsumer#onError(Consumer)} will not called in that case.
	 * @param t the {@link Throwable}
	 */
	public void doExternalError(Throwable t) {
		if (!error.compareAndSet(null, t)) {
			throw new IllegalStateException("An error has already been thrown");
		}
	}
	
	/**
	 * Sets an external close to the stream, that will cause the underlying stream to be closed.
	 * This takes only effect, if {@link DistributedConsumer#doConnect} was called before. 
	 * The {@link DistributedConsumer#onClose(Consumer)} will not called in that case.
	 * @param t the {@link Throwable}
	 */
	public void doExternalClose() {
		if (close.compareAndSet(false, true) && connected) {
			stream.close();
		}
	}
	
	/**
	 * Closes an disconnects the consumer
	 */
	public void close() {
		connected = false;
		errorFunction.set(null);
		error.set(null);
		closeFunction.set(null);
		close.set(false);
		acceptFunction.set(null);
		stream.close();
	}
	
	/**
	 * Checks, if an external error was set and re-throws it
	 * @throws Exception
	 */
	private void checkExternalError() throws Exception {
		Throwable t = error.get();
		if (t != null) {
			throw (Exception)t;
		}
	}
	
	/**
	 * Handles a close event
	 */
	private void handleClose() {
		if (close.compareAndSet(true, false)) {
			return;
		}
		Runnable closeHandler = closeFunction.get();
		if (closeHandler != null) {
			closeHandler.run();
		}
	}
	
	/**
	 * Handles an error event
	 * @param t the error to handle
	 */
	private void handleError(Throwable t) {
		if (error.compareAndSet(t, null)) {
			return;
		}
		Consumer<Throwable> errorHandler = errorFunction.get();
		if (errorHandler != null) {
			errorHandler.accept(t);
		}
	}
	
	/**
	 * Handles an accept
	 * @param data the data to handle
	 */
	@SuppressWarnings("unchecked")
	private void handleAccept(Object data) {
		Consumer<T> acceptHandler = acceptFunction.get();
		if (acceptHandler != null) {
			acceptHandler.accept((T)data);
		}
	}

}
