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

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 
 * @author mark
 * @since 27.01.2019
 */
public class GBQueuePolicy implements GBQueuePolicyMBean {
	
	private final AtomicInteger gradeValue = new AtomicInteger();
	private final AtomicLong waitTime = new AtomicLong();
	private int grade;
	private long waitValue;
	
	/**
	 * Creates a new instance.
	 */
	public GBQueuePolicy() {
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.GBQueuePolicyMBean#getFillGrade()
	 */
	@Override
	public int getCurrentBufferFillGrade() throws IOException {
		return gradeValue.get();
	}
	
	public void setFillGrade(int value) {
		gradeValue.set(value);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.GBQueuePolicyMBean#getBreakGrade()
	 */
	@Override
	public int getBreakThreshold() throws IOException {
		return grade;
	}
	
	public void setBreakThreshold(int value) {
		this.grade = value;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.GBQueuePolicyMBean#getWaitTime()
	 */
	@Override
	public long getWaitTime() throws IOException {
		return waitValue;
	}
	
	public void setWaitTime(long value) {
		this.waitValue = value;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.GBQueuePolicyMBean#getCurrentWaitTime()
	 */
	@Override
	public long getCurrentWaitTime() throws IOException {
		return waitTime.get();
	}

	
	public void setCurrentWaitTime(long value) {
		this.waitTime.set(value);
	}
}
