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

/**
 * Close callback that wraps the original close function into another one.
 * So, two callbacks can be called from this callback.
 * 
 * @author Mark Hoffmann
 */
public class DistributedOnCloseCallback implements Runnable {
	
	private AtomicReference<Runnable> onCloseHandler = new AtomicReference<>(); 	
	private AtomicReference<Runnable> distOnCloseHandler = new AtomicReference<>();
	
	public static DistributedOnCloseCallback create() {
		return new DistributedOnCloseCallback();
	}
	
	/**
	 * Sets the on close handler
	 * @param onClose the handler to set
	 * @return the instances
	 */
	public DistributedOnCloseCallback withOnClose(Runnable onClose) {
		if(!onCloseHandler.compareAndSet(null, onClose)) {
			throw new IllegalStateException("A close handler has already been defined for this stream object");
		}
		return this;
	}
	
	/**
	 * Sets a distributed on close handler;
	 * @param distOnClose the handler to set
	 * @return the callback instance
	 */
	public DistributedOnCloseCallback withDistributedOnClose(Runnable distOnClose) {
		if(!distOnCloseHandler.compareAndSet(null, distOnClose)) {
			throw new IllegalStateException("A distributed close handler has already been defined for this stream object");
		}
		return this;
	}
	

	/* 
	 * (non-Javadoc)
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		Runnable distOnClose = distOnCloseHandler.getAndSet(null);
		if (distOnClose != null) {
			distOnClose.run();
		}
		Runnable onClose = onCloseHandler.getAndSet(null);
		if (onClose != null) {
			onClose.run();
		}
	}

}
