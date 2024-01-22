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
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.core.api.jmx.MBeanable;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushbackPolicy;

/**
 * Pushback policy that calculates the back-pressure
 * @author mark
 * @since 27.01.2019
 */
public class FixedGradePushbackPolicy<T, U extends BlockingQueue<PushEvent<? extends T>>> implements PushbackPolicy<T, U>, MBeanable {
	
	private final static Logger logger = Logger.getLogger(FixedGradePushbackPolicy.class.getName());
	private final String id;
	private final int fillGrade;
	private final int bufferSize;
	private final long defaultWaitValue;
	private final AtomicLong cnt = new AtomicLong();
	private final AtomicInteger backoffCount = new AtomicInteger(0);
	private final FXPushbackPolicy mbean;

	/**
	 * Creates a new instance.
	 */
	public FixedGradePushbackPolicy(String id, int fillGrade, int bufferSize, long defaultWaitTime) {
		this.id = id;
		this.fillGrade = fillGrade < 1 ? 1 : fillGrade < 100 ? 100 : fillGrade;
		this.bufferSize = bufferSize < 0 ? -1 : bufferSize;
		this.defaultWaitValue = defaultWaitTime < 0 ? 0 : defaultWaitTime;
		mbean = new FXPushbackPolicy();
		mbean.setBreakThreshold(fillGrade);
		mbean.setWait(defaultWaitTime);
	}
	
	/**
	 * Creates a new instance.
	 */
	public FixedGradePushbackPolicy(String id, int grade, long waitValue) {
		this.id = id;
		this.fillGrade = grade;
		this.bufferSize = -1;
		this.defaultWaitValue = waitValue;
		mbean = new FXPushbackPolicy();
		mbean.setBreakThreshold(grade);
		mbean.setWait(waitValue);
	}
	
	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.common.MBeanable#getMBean()
	 */
	public FXPushbackPolicyMBean getMBean() {
		return mbean;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushbackPolicy#pushback(java.util.concurrent.BlockingQueue)
	 */
	@Override
	public long pushback(U queue) throws Exception {
		int size;
		if (bufferSize == -1) {
			size = queue.size() + queue.remainingCapacity();
		} else {
			size = bufferSize;
		}
		double oneP = (double)size / (double)100;
		int gradeValue = size - (int)(oneP * fillGrade);
		int currentGrade = (int)(oneP * (double)queue.size());
		mbean.setCurrentBufferFillGrade(currentGrade);
		if (cnt.incrementAndGet() % 100 == 0) {
			logger.log(Level.INFO, String.format("[%s] Buffersize: %s , remaining elements: %s, remaining fill grade: %s", id, size, queue.remainingCapacity(), gradeValue));
		}
		if (queue.remainingCapacity() < gradeValue) {
			long bp = (gradeValue - queue.remainingCapacity()) * defaultWaitValue;
			if (cnt.incrementAndGet() % 20 == 0) {
				logger.log(Level.INFO, String.format("[%s] Backpressure: %s , remaining: %s, remaining fill grade: %s", id, bp, queue.remainingCapacity(), gradeValue));
			}
			mbean.setCurrentBackPressure(bp);
			return bp;
		}
		mbean.setCurrentBackPressure(0);
		backoffCount.set(0);
		return 0;
	}

}
