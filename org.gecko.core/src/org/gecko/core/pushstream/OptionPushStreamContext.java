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

import org.gecko.core.pushstream.policy.GeckoPushbackPolicyOption;
import org.osgi.util.function.Consumer;
import org.osgi.util.function.Predicate;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushbackPolicy;
import org.osgi.util.pushstream.PushbackPolicyOption;
import org.osgi.util.pushstream.QueuePolicy;
import org.osgi.util.pushstream.QueuePolicyOption;

/**
 * {@link PushStreamContext} that gets the information out of an property map
 * @author Mark Hoffmann
 * @since 03.01.2019
 */
public class OptionPushStreamContext<T> implements PushStreamContext<T>, PushStreamConstants {
	
	private final Map<String, Object> options;

	/**
	 * Creates a new instance.
	 */
	public OptionPushStreamContext(Map<String, Object> options) {
		if (options != null && !options.isEmpty()) {
			this.options = Collections.unmodifiableMap(options);
		} else {
			this.options = Collections.emptyMap();
		}
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.PushStreamContext#getBufferSize()
	 */
	@Override
	public int getBufferSize() {
		Integer bs = getValue(PROP_BUFFER_SIZE, Integer.class, Integer.valueOf(0));
		return bs;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.PushStreamContext#getParallelism()
	 */
	@Override
	public int getParallelism() {
		Integer p = getValue(PROP_PARALLELISM, Integer.class, Integer.valueOf(1));
		return p;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.PushStreamContext#getExecutor()
	 */
	@Override
	public ExecutorService getExecutor() {
		return getValue(PROP_EXECUTOR, ExecutorService.class, null);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.PushStreamContext#getScheduler()
	 */
	@Override
	public ScheduledExecutorService getScheduler() {
		return getValue(PROP_SCHEDULED_EXECUTOR, ScheduledExecutorService.class, null);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.PushStreamContext#getBufferQueue()
	 */
	@SuppressWarnings("unchecked")
	@Override
	public BlockingQueue<PushEvent<? extends T>> getBufferQueue() {
		return getValue(PROP_BUFFER_QUEUE, BlockingQueue.class, null);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.PushStreamContext#getQueuePolicy()
	 */
	@SuppressWarnings("unchecked")
	@Override
	public QueuePolicy<T, BlockingQueue<PushEvent<? extends T>>> getQueuePolicy() {
		return getValue(PROP_QUEUE_POLICY, QueuePolicy.class, null);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.PushStreamContext#getQueuePolicyOption()
	 */
	@Override
	public QueuePolicyOption getQueuePolicyOption() {
		return getValue(PROP_QUEUE_POLICY_OPTION, QueuePolicyOption.class, null);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.PushStreamContext#getPushbackPolicy()
	 */
	@SuppressWarnings("unchecked")
	@Override
	public PushbackPolicy<T, BlockingQueue<PushEvent<? extends T>>> getPushbackPolicy() {
		return getValue(PROP_PUSHBACK_POLICY, PushbackPolicy.class, null);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.PushStreamContext#getPushbackPolicyOption()
	 */
	@Override
	public PushbackPolicyOption getPushbackPolicyOption() {
		return getValue(PROP_PUSHBACK_POLICY_OPTION, PushbackPolicyOption.class, null);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.PushStreamContext#getPushbackPolicyByName()
	 */
	@Override
	public <U extends BlockingQueue<PushEvent<? extends T>>> PushbackPolicy<T, U> getPushbackPolicyByName() {
		String name = getValue(PROP_PUSHBACK_POLICY_OPTION_BY_NAME, String.class, null);
		Long time = getPushbackPolicyOptionTime();
		if(time == null) {
			throw new IllegalArgumentException(PROP_PUSHBACK_POLICY_OPTION_BY_NAME + " requires " + PROP_PUSHBACK_POLICY_TIME + "to be set");
		}
		PushbackPolicyOption option = null;
		for (PushbackPolicyOption pushbackPolicyOption : PushbackPolicyOption.values()) {
			if(pushbackPolicyOption.name().equals(name)) {
				option = pushbackPolicyOption;
				break;
			}
		}
		if(option == null) {
			GeckoPushbackPolicyOption pushbackPolicyOption = null;
			for (GeckoPushbackPolicyOption geckoOption : GeckoPushbackPolicyOption.values()) {
				if(geckoOption.name().equals(name)) {
					pushbackPolicyOption = geckoOption;
					break;
				}
			}
			if(pushbackPolicyOption == null) {
				throw new IllegalArgumentException("No PushbackPolicyOption or GeckoPushbackPolicyOption found with name " + name);
			}
			return pushbackPolicyOption.getPolicy(time);
		} else {
			return option.getPolicy(time);
		}
	}
	
	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.PushStreamContext#getPushbackPolicyOptionTime()
	 */
	@Override
	public Long getPushbackPolicyOptionTime() {
		Long t = getValue(PROP_PUSHBACK_POLICY_TIME, Long.class, null); 
		return t;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.PushStreamContext#getAcknowledgeFilter()
	 */
	@SuppressWarnings("unchecked")
	@Override
	public Predicate<T> getAcknowledgeFilter() {
		return  getValue(PROP_ACK_FILTER, Predicate.class, null);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.PushStreamContext#getAcknowledgeFunction()
	 */
	@SuppressWarnings("unchecked")
	@Override
	public Consumer<T> getAcknowledgeFunction() {
		return getValue(PROP_ACK_CONSUMER, Consumer.class, null);
	}
	
	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.PushStreamContext#getNAcknowledgeFunction()
	 */
	@SuppressWarnings("unchecked")
	@Override
	public Consumer<T> getNAcknowledgeFunction() {
		return getValue(PROP_NACK_CONSUMER, Consumer.class, null);
	}
	
	/* 
	 * (non-Javadoc)
	 * @see org.gecko.util.pushstreams.PushStreamContext#getAcknowledgeErrorFunction()
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
	 * @see org.gecko.util.pushstreams.PushStreamContext#getQueuePolicyByName()
	 */
	@Override
	public QueuePolicy<T, BlockingQueue<PushEvent<? extends T>>> getQueuePolicyByName() {
		String name = getValue(PROP_QUEUE_POLICY_BY_NAME, String.class, null);
		return PushStreamContext.getQueuePolicyByName(name);
	}

}
