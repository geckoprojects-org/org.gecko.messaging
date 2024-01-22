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

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Error callback that wraps the original error function into another one.
 * So, two callbacks can be called from this callback.
 * 
 * @author Mark Hoffmann
 */
public class DistributedOnErrorCallback implements Consumer<Throwable> {

	private AtomicReference<Consumer<? super Throwable>> onErrorHandler = new AtomicReference<>(); 	
	private AtomicReference<Consumer<? super Throwable>> distOnErrorHandler = new AtomicReference<>();

	public static DistributedOnErrorCallback create() {
		return new DistributedOnErrorCallback();
	}

	/**
	 * Sets the on error handler
	 * @param onError the handler to set
	 * @return the instances
	 */
	public DistributedOnErrorCallback withOnError(Consumer<? super Throwable> onError) {
		if(!onErrorHandler.compareAndSet(null, onError)) {
			throw new IllegalStateException("A error handler has already been defined for this stream object");
		}
		return this;
	}

	/**
	 * Sets a distributed on error handler;
	 * @param distOnError the handler to set
	 * @return the callback instance
	 */
	public DistributedOnErrorCallback withDistributedOnError(Consumer<? super Throwable> distOnError) {
		if(!distOnErrorHandler.compareAndSet(null, distOnError)) {
			throw new IllegalStateException("A distributed error handler has already been defined for this stream object");
		}
		return this;
	}


	/* (non-Javadoc)
	 * @see java.util.function.Consumer#accept(java.lang.Object)
	 */
	@Override
	public synchronized void accept(Throwable t) {
		Consumer<? super Throwable> distOnError = distOnErrorHandler.getAndSet(null);
		if (distOnError != null) {
			distOnError.accept(t);
		}
		Consumer<? super Throwable> onError = onErrorHandler.getAndSet(null);
		if (onError != null) {
			onError.accept(t);
		}
	}

}
