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

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;

import org.osgi.util.function.Consumer;
import org.osgi.util.function.Predicate;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.QueuePolicy;
import org.osgi.util.pushstream.QueuePolicyOption;

/**
 * {@link PushStreamContext} that gets the information out of an property map
 * @author Mark Hoffmann
 * @since 03.01.2019
 */
public class OptionSimpleEventSourceContext<T> implements SimplePushEventSourceContext<T>, PushStreamConstants {
	
	private final Map<String, Object> options;

	/**
	 * Creates a new instance.
	 */
	public OptionSimpleEventSourceContext(Map<String, Object> options) {
		if (options != null && !options.isEmpty()) {
			this.options = Collections.unmodifiableMap(options);
		} else {
			this.options = Collections.emptyMap();
		}
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.SimplePushEventSourceContext#getBufferSize()
	 */
	@Override
	public int getBufferSize() {
		Integer bs = getValue(PROP_SES_BUFFER_SIZE, Integer.class, Integer.valueOf(0));
		return bs;
	}


	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.SimplePushEventSourceContext#getBufferQueue()
	 */
	@SuppressWarnings("unchecked")
	@Override
	public BlockingQueue<PushEvent<? extends T>> getBufferQueue() {
		return getValue(PROP_SES_BUFFER_QUEUE, BlockingQueue.class, null);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.SimplePushEventSourceContext#getQueuePolicyByName()
	 */
	@Override
	public QueuePolicy<T, BlockingQueue<PushEvent<? extends T>>> getQueuePolicyByName() {
		String name = getValue(PROP_SES_QUEUE_POLICY_BY_NAME, String.class, null);
		return PushStreamContext.getQueuePolicyByName(name);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.SimplePushEventSourceContext#getQueuePolicy()
	 */
	@SuppressWarnings("unchecked")
	@Override
	public QueuePolicy<T, BlockingQueue<PushEvent<? extends T>>> getQueuePolicy() {
		return getValue(PROP_SES_QUEUE_POLICY, QueuePolicy.class, null);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.SimplePushEventSourceContext#getQueuePolicyOption()
	 */
	@Override
	public QueuePolicyOption getQueuePolicyOption() {
		return getValue(PROP_SES_QUEUE_POLICY_OPTION, QueuePolicyOption.class, null);
	}


	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.SimplePushEventSourceContext#getAcknowledgeFilter()
	 */
	@SuppressWarnings("unchecked")
	@Override
	public Predicate<T> getAcknowledgeFilter() {
		return  getValue(PROP_ACK_FILTER, Predicate.class, null);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.SimplePushEventSourceContext#getAcknowledgeFunction()
	 */
	@SuppressWarnings("unchecked")
	@Override
	public Consumer<T> getAcknowledgeFunction() {
		return getValue(PROP_ACK_CONSUMER, Consumer.class, null);
	}
	
	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.SimplePushEventSourceContext#getNAcknowledgeFunction()
	 */
	@SuppressWarnings("unchecked")
	@Override
	public Consumer<T> getNAcknowledgeFunction() {
		return getValue(PROP_NACK_CONSUMER, Consumer.class, null);
	}
	
	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.SimplePushEventSourceContext#getAcknowledgeErrorFunction()
	 */
	@SuppressWarnings("unchecked")
	@Override
	public BiConsumer<Throwable, T> getAcknowledgeErrorFunction() {
		return getValue(PROP_ACK_ERROR, BiConsumer.class, null);
	}

	/**
	 * Returns the value of the key or <code>null</code>. If the provided class does not fit to the value's class an {@link IllegalStateException} will be thrown 
	 * @param key the options key
	 * @param clazz the class type of the expected value
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private <O> O getValue(String key, Class<O> clazz, O defaultValue) {
		Object o = options.getOrDefault(key, defaultValue);
		if (o == null) {
			return null;
		}
		if (clazz.isAssignableFrom(o.getClass())) {
			return (O)o;
		} else {
			throw new IllegalStateException(String.format("The value of key '%s' is exepected of type '%s', but was of type '%s'", key, clazz.getName(), o.getClass().getName()));
		}
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.SimplePushEventSourceContext#getParallelism()
	 */
	@Override
	public int getParallelism() {
		Integer p = getValue(PROP_PARALLELISM, Integer.class, Integer.valueOf(1));
		return p;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.SimplePushEventSourceContext#getExecutor()
	 */
	@Override
	public ExecutorService getExecutor() {
		return getValue(PROP_EXECUTOR, ExecutorService.class, null);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.SimplePushEventSourceContext#getScheduler()
	 */
	@Override
	public ScheduledExecutorService getScheduler() {
		return getValue(PROP_SCHEDULED_EXECUTOR, ScheduledExecutorService.class, null);
	}

}
