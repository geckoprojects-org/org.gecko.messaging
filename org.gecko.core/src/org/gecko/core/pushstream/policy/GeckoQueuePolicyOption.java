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

import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.QueuePolicy;

/**
 * Enum for useful {@link QueuePolicy} instances
 * @author Mark Hoffmann
 * @since 28.01.2019
 */
public enum GeckoQueuePolicyOption {
	
	/**
	 * Returns a gradual breaking policy with a buffer size of 100 and a treshold of 80% to start breaking the
	 * the enqueue in the buffer and a default wait time of 5ms
	 */
	GRADUAL_BREAKING_POLICY {
		/* 
		 * (non-Javadoc)
		 * @see org.gecko.util.pushstreams.GeckoQueuePolicyOption#getPolicy(long)
		 */
		@Override
		public <T, U extends BlockingQueue<PushEvent<? extends T>>> QueuePolicy<T, U> getPolicy() {
			return new GradualBreakingQueuePolicy<T, U>("GRADUAL_BREAKING_POLICY", 80, 100, 5);
		}
	};
	
	/**
	 * Create a {@link QueuePolicy} instance configured with default values
	 * 
	 * @return A {@link QueuePolicy} to use
	 */
	public abstract <T, U extends BlockingQueue<PushEvent<? extends T>>> QueuePolicy<T, U> getPolicy();

}
