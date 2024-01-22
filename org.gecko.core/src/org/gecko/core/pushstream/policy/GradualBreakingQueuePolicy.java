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
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.core.api.jmx.MBeanable;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.QueuePolicy;
import org.osgi.util.pushstream.QueuePolicyOption;

/**
 * {@link QueuePolicy} that starts breaking at a certain fill grade of the buffer.
 * A given grade of 100% results into a the same like the {@link QueuePolicyOption#BLOCK}
 * @author Mark Hoffmann
 * @since 27.01.2019
 */
public class GradualBreakingQueuePolicy<T, U extends BlockingQueue<PushEvent<? extends T>>> implements QueuePolicy<T, U>, MBeanable {

	private final static Logger logger = Logger.getLogger(GradualBreakingQueuePolicy.class.getName());
	private final String id;
	private final int fillGrade;
	private final int bufferSize;
	private final long defaultWaitValue;
	private final AtomicLong cnt = new AtomicLong();
	private final GBQueuePolicy mbean;

	/**
	 * Creates a new instance.
	 */
	public GradualBreakingQueuePolicy(String id, int fillGrade, int bufferSize, long defaultWaitTime) {
		this.id = id;
		this.fillGrade = fillGrade < 1 ? 1 : fillGrade > 100 ? 100 : fillGrade;
		this.bufferSize = bufferSize < 0 ? -1 : bufferSize;
		this.defaultWaitValue = defaultWaitTime < 0 ? 0 : defaultWaitTime;
		mbean = new GBQueuePolicy();
		mbean.setBreakThreshold(fillGrade);
		mbean.setWaitTime(defaultWaitValue);
	}
	
	/**
	 * Creates a new instance.
	 */
	public GradualBreakingQueuePolicy(String id, int fillGrade, long defaultWaitTime) {
		this(id, fillGrade, -1, defaultWaitTime);
	}
	
	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.common.MBeanable#getMBean()
	 */
	public GBQueuePolicyMBean getMBean() {
		return mbean;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.QueuePolicy#doOffer(java.util.concurrent.BlockingQueue, org.osgi.util.pushstream.PushEvent)
	 */
	@Override
	public void doOffer(U queue, PushEvent<? extends T> event) throws Exception {
		int size;
		if (bufferSize == -1) {
			size = queue.size() + queue.remainingCapacity();
		} else {
			size = bufferSize;
		}
		try {
			double oneP = (double)size / (double)100;
			int gradeValue = size - (int)(oneP * fillGrade);
			int currentGrade = size / 100 * queue.size();
			mbean.setFillGrade(currentGrade);
			if (cnt.incrementAndGet() % 100 == 0) {
				logger.log(Level.INFO, String.format("[%s] Buffersize: %s , remaining elements: %s, remaining fill grade: %s", id, size, queue.remainingCapacity(), gradeValue));
			}
			/**
			 * Blocking policy
			 */
			if (fillGrade == 100 && queue.remainingCapacity() == 0) {
				long time = System.currentTimeMillis();
				queue.put(event);
				mbean.setCurrentWaitTime(System.currentTimeMillis() - time);
				return;
			}
			/**
			 * Remaining fill-grade policy 
			 */
			if (queue.remainingCapacity() <= gradeValue) {
				long bp = (gradeValue - queue.remainingCapacity()) * defaultWaitValue;
				if (bp >= 0) {
					mbean.setCurrentWaitTime(bp);
					if (cnt.get() % 20 == 0) {
						logger.log(Level.INFO, String.format("[%s] Breaking offer for: %s ms, remaining elements: %s, remaining fill grade: %s", id, bp, queue.remainingCapacity(), gradeValue));
					}
					Thread.sleep(bp);
				}
			} else {
				mbean.setCurrentWaitTime(0);
			}
			queue.put(event);
		} catch (Exception e) {
			logger.log(Level.SEVERE, String.format("[%s] Interupted waiting for timeout",id), e);
		}
	}

}
