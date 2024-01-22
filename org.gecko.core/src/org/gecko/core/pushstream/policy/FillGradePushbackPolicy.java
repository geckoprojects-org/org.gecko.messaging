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
package org.gecko.core.pushstream.policy;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushbackPolicy;

/**
 * 
 * @author mark
 * @since 25.01.2019
 */
public class FillGradePushbackPolicy<T, U extends BlockingQueue<PushEvent<? extends T>>> implements PushbackPolicy<T, U> {
	
	private int grade;
	private int value;

	/**
	 * Creates a new instance.
	 */
	private FillGradePushbackPolicy(int grade, int value) {
		this.value = value;
		this.grade = grade < 0 ? 0 : grade > 100 ? 100 : grade;
	}
	
	
	public static <T, U extends BlockingQueue<PushEvent<? extends T>>> PushbackPolicy<T, U> createFillGradePushbackPolicy(int grade, int value) {
		return new FillGradePushbackPolicy<T, U>(grade, value);
	}
	
	public static <T, U extends BlockingQueue<PushEvent<? extends T>>> PushbackPolicy<T, U> createFillGradePushbackPolicy(int value) {
		return new FillGradePushbackPolicy<T, U>(80, value);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushbackPolicy#pushback(java.util.concurrent.BlockingQueue)
	 */
	@Override
	public long pushback(U queue) throws Exception {
		int size = queue.size();
		int remainingCap = size - ((size / 100) * grade);
		AtomicInteger backoffCount = new AtomicInteger(0);
		if (queue.remainingCapacity() <= remainingCap) {
			return value << backoffCount.getAndIncrement();
		}
		backoffCount.set(0);
		return 0;
	}

}
