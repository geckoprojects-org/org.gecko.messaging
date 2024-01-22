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
public class FXPushbackPolicy implements FXPushbackPolicyMBean {
	
	private final AtomicInteger gradeValue = new AtomicInteger();
	private final AtomicLong waitTime = new AtomicLong();
	private int breakGrade;
	private long wait;
	
	/**
	 * Creates a new instance.
	 */
	public FXPushbackPolicy() {
	}
	
	

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.FXPushbackPolicyMBean#getFillGrade()
	 */
	@Override
	public int getCurrentBufferFillGrade() throws IOException {
		return gradeValue.get();
	}
	
	public void setCurrentBufferFillGrade(int grade) {
		this.gradeValue.set(grade);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.FXPushbackPolicyMBean#getBreakGrade()
	 */
	@Override
	public int getBreakThreshold() throws IOException {
		return breakGrade;
	}
	
	/**
	 * Sets the breakGrade.
	 * @param breakGrade the breakGrade to set
	 */
	public void setBreakThreshold(int breakGrade) {
		this.breakGrade = breakGrade;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.FXPushbackPolicyMBean#getWaitTime()
	 */
	@Override
	public long getWaitTime() throws IOException {
		return wait;
	}
	
	public void setCurrentBackPressure(long waitTime) {
		this.waitTime.set(waitTime);
	}
	
	/**
	 * Sets the wait.
	 * @param wait the wait to set
	 */
	public void setWait(long wait) {
		this.wait = wait;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.FXPushbackPolicyMBean#getCurrentBackpressure()
	 */
	@Override
	public long getCurrentBackpressure() throws IOException {
		return waitTime.get();
	}

}
