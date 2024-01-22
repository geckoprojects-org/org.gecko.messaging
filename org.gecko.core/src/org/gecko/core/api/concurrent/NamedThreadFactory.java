/**
 * Copyright (c) 2012 - 2018 Data In Motion and others.
 * All rights reserved. 
 * 
 * This program and the accompanying materials are made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
package org.gecko.core.api.concurrent;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple named factory implementation
 * @author Mark Hoffmann
 * @since 29.11.2018
 */
public class NamedThreadFactory implements ThreadFactory {
	
	private final String name;
	private final AtomicLong count = new AtomicLong();
	
	/**
	 * Factory method to create a new names thread factory
	 * @param name the name of the factory
	 * @return the thread factory instance
	 */
	public static ThreadFactory newNamedFactory(String name) {
		return new NamedThreadFactory(name);
	}
	
	/**
	 * Creates a new instance.
	 */
	NamedThreadFactory(String name) {
		this.name = name;
	}

	/* 
	 * (non-Javadoc)
	 * @see java.util.concurrent.ThreadFactory#newThread(java.lang.Runnable)
	 */
	@Override
	public Thread newThread(Runnable r) {
		String threadName = name == null ? null : name + "-" + count.getAndIncrement();
		Thread t = threadName == null ? new Thread(r) :  new Thread(r, threadName);
		return t;
	}

}
