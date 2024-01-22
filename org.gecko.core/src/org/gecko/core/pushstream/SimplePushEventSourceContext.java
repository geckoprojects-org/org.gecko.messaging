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
package org.gecko.core.pushstream;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;

import org.osgi.util.function.Consumer;
import org.osgi.util.function.Predicate;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.QueuePolicy;
import org.osgi.util.pushstream.QueuePolicyOption;

/**
 * Context interface for configuring a Pushstream
 * @author Mark Hoffmann
 * @since 03.01.2019
 */
public interface SimplePushEventSourceContext<T> {
	
	/**
	 * Returns the buffer size, that should be used in the {@link PushStream}. Valid values are larger than 0
	 * @return the buffer size
	 */
	int getBufferSize();
	
	/**
	 * Returns the parallelism. Default is 1. Any values larger than 1 are set
	 * @return the parallelism
	 */
	int getParallelism();
	
	/**
	 * Returns the executor service to be used to create the threads
	 * @return the executor service
	 */
	ExecutorService getExecutor();
	
	/**
	 * Returns the scheduled executor to be used
	 * @return the scheduled executor to be used
	 */
	ScheduledExecutorService getScheduler();
	
	/**
	 * Returns a buffer queue to be used
	 * @return a buffer queue to be used
	 */
	BlockingQueue<PushEvent<? extends T>> getBufferQueue();
	
	/**
	 * Returns the queue policy
	 * @return the queue policy
	 */
	QueuePolicy<T, BlockingQueue<PushEvent<? extends T>>> getQueuePolicy();
	
	/**
	 * Returns the queue policy by name
	 * @return the queue policy
	 */
	QueuePolicy<T, BlockingQueue<PushEvent<? extends T>>> getQueuePolicyByName();
	
	/**
	 * Returns the used queue policy option
	 * @return the used queue policy option
	 */
	QueuePolicyOption getQueuePolicyOption();
	
	/**
	 * Returns the acknowledge filter predicate
	 * @return the acknowledge filter predicate
	 */
	Predicate<T> getAcknowledgeFilter();
	
	/**
	 * Returns the consumer for the acknowledge function 
	 * @return the consumer for the acknowledge function 
	 */
	Consumer<T> getAcknowledgeFunction();
	
	/**
	 * Returns the consumer for the negative acknowledge function 
	 * @return the consumer for the negative acknowledge function 
	 */
	Consumer<T> getNAcknowledgeFunction();
	
	/**
	 * Returns the consumer for the acknowledge error function 
	 * @return the consumer for the acknowledge error function 
	 */
	BiConsumer<Throwable, T> getAcknowledgeErrorFunction();
	
}
