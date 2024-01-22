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
package org.gecko.core.pushstream;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.gecko.core.pushstream.source.AcknowledgingEventSource;
import org.osgi.util.pushstream.BufferBuilder;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushEventSource;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamBuilder;
import org.osgi.util.pushstream.PushStreamProvider;
import org.osgi.util.pushstream.PushbackPolicyOption;
import org.osgi.util.pushstream.SimplePushEventSource;

/**
 * Helper class to configure push streams
 * @author Mark Hoffmann
 * @since 29.11.2018
 */
public class PushStreamHelper implements PushStreamConstants{
	
	/**
	 * Creates a {@link PushStream} instance out of the given configuration and builder
	 * @param builder the {@link PushStreamBuilder} instance, must not be <code>null</code>
	 * @param context the {@link PushStreamContext} with the configuration data
	 * @return {@link PushStream} instance
	 */
	public static <T> PushStream<T> createPushStream(PushStreamBuilder<T, BlockingQueue<PushEvent<? extends T>>> builder, PushStreamContext<T> context) {
		if (builder == null) {
			throw new IllegalArgumentException("Cannot create push stream from null builder");
		}
		builder = configurePushStreamBuilder(builder, context);
		return builder.build();
	}
	
	/**
	 * Creates a {@link PushStream} instance out of the given configuration and {@link PushEventSource}
	 * @param source the {@link PushEventSource} instance, must not be <code>null</code>
	 * @param context the {@link PushStreamContext} with the configuration data
	 * @return {@link PushStream} instance
	 */
	public static <T> PushStream<T> createPushStream(PushEventSource<T> source, PushStreamContext<T> context) {
		if (source == null) {
			throw new IllegalArgumentException("An event source must not be null to create a PushStream");
		}
		PushStreamBuilder<T, BlockingQueue<PushEvent<? extends T>>> builder = configurePushStreamBuilder(source, context);
		return builder.build();
	}
	
	/**
	 * Configures an {@link PushStreamBuilder} with the given {@link PushStreamContext}
	 * @param builder the {@link PushStreamBuilder} instance, must not be <code>null</code>
	 * @param context the {@link PushStreamContext} with the configuration data
	 * @return {@link PushStreamBuilder} instance
	 */
	public static <T> PushStreamBuilder<T, BlockingQueue<PushEvent<? extends T>>> configurePushStreamBuilder(PushStreamBuilder<T, BlockingQueue<PushEvent<? extends T>>> builder, PushStreamContext<T> context) {
		if (builder == null) {
			throw new IllegalArgumentException("Cannot configure a push stream builder from null instance");
		}
		if (context != null) {
			if (context.getBufferQueue() != null) {
				builder.withBuffer(context.getBufferQueue());
			}
			if (context.getExecutor() != null) {
				builder.withExecutor(context.getExecutor());
			}
			if (context.getScheduler() != null) {
				builder.withScheduler(context.getScheduler());
			}
			if (context.getParallelism() > 1) {
				builder.withParallelism(context.getParallelism());
			}
			if (context.getBufferSize() > 0 && context.getBufferQueue() == null) {
				builder.withBuffer(new LinkedBlockingQueue<PushEvent<? extends T>>(context.getBufferSize()));
			}
			if (context.getQueuePolicy() != null) {
				builder.withQueuePolicy(context.getQueuePolicy());
			}
			if (context.getQueuePolicyOption() != null) {
				builder.withQueuePolicy(context.getQueuePolicyOption());
			}
			if (context.getQueuePolicyByName() != null) {
				builder.withQueuePolicy(context.getQueuePolicyByName());
			}
			if (context.getPushbackPolicy() != null) {
				builder.withPushbackPolicy(context.getPushbackPolicy());
			} else {
				builder.withPushbackPolicy(PushbackPolicyOption.ON_FULL_FIXED, 10);
			}
			if (context.getPushbackPolicyOption() != null && context.getPushbackPolicyOptionTime() != null) {
				builder.withPushbackPolicy(context.getPushbackPolicyOption(), context.getPushbackPolicyOptionTime().longValue());
			}
		}
		return builder;
	}
	
	/**
	 * Configures an {@link PushStreamBuilder} with the given {@link PushStreamContext} and {@link PushEventSource}
	 * @param source the {@link PushEventSource} instance, must not be <code>null</code>
	 * @param context the {@link PushStreamContext} with the configuration data
	 * @return {@link PushStreamBuilder} instance
	 */
	public static <T> PushStreamBuilder<T, BlockingQueue<PushEvent<? extends T>>> configurePushStreamBuilder(PushEventSource<T> source, PushStreamContext<T> context) {
		if (source == null) {
			throw new IllegalArgumentException("An event source must not be null to create a PushStreamBuilder");
		}
		PushStreamProvider psp = new PushStreamProvider();
		PushStreamBuilder<T, BlockingQueue<PushEvent<? extends T>>> builder = psp.buildStream(source);
		return configurePushStreamBuilder(builder, context);
	}
	

	/**
	 * Create an options map out of the {@link PushStreamContext}. 
	 * @param context the context
	 * @return the options map or an empty map. Will never be <code>null</code>
	 */
	public static Map<String, Object> getPushStreamOptions(PushStreamContext<?> context) {
		if (context == null) {
			return Collections.emptyMap();
		}
		Map<String, Object> options = new HashMap<String, Object>();
		if (context.getBufferQueue() != null) {
			options.put(PROP_BUFFER_QUEUE, context.getBufferQueue());
		}
		if (context.getExecutor() != null) {
			options.put(PROP_EXECUTOR, context.getExecutor());
		}
		if (context.getScheduler() != null) {
			options.put(PROP_SCHEDULED_EXECUTOR, context.getScheduler());
		}
		if (context.getParallelism() > 1) {
			options.put(PROP_PARALLELISM, context.getParallelism());
		}
		if (context.getBufferSize() > 0 && context.getBufferQueue() == null) {
			options.put(PROP_BUFFER_SIZE, context.getBufferSize());
		}
		if (context.getPushbackPolicy() != null) {
			options.put(PROP_PUSHBACK_POLICY, context.getPushbackPolicy());
		}
		if (context.getPushbackPolicyOption() != null) {
			options.put(PROP_PUSHBACK_POLICY_OPTION, context.getPushbackPolicyOption());
		}
		if (context.getQueuePolicy() != null) {
			options.put(PROP_QUEUE_POLICY, context.getQueuePolicy());
		}
		if (context.getQueuePolicyOption() != null) {
			options.put(PROP_QUEUE_POLICY_OPTION, context.getQueuePolicyOption());
		}
		return options;
	}
	
	/**
	 * Create an options map out of the {@link PushStreamContext}. 
	 * @param context the context
	 * @return the options map or an empty map. Will never be <code>null</code>
	 */
	public static Map<String, Object> getSimpleEventSourceOptions(SimplePushEventSourceContext<?> context) {
		if (context == null) {
			return Collections.emptyMap();
		}
		Map<String, Object> options = new HashMap<String, Object>();
		if (context.getBufferQueue() != null) {
			options.put(PROP_SES_BUFFER_QUEUE, context.getBufferQueue());
		}
		if (context.getExecutor() != null) {
			options.put(PROP_EXECUTOR, context.getExecutor());
		}
		if (context.getScheduler() != null) {
			options.put(PROP_SCHEDULED_EXECUTOR, context.getScheduler());
		}
		if (context.getParallelism() > 1) {
			options.put(PROP_PARALLELISM, context.getParallelism());
		}
		if (context.getBufferSize() > 0 && context.getBufferQueue() == null) {
			options.put(PROP_SES_BUFFER_SIZE, context.getBufferSize());
		}
		if (context.getQueuePolicy() != null) {
			options.put(PROP_SES_QUEUE_POLICY, context.getQueuePolicy());
		}
		if (context.getQueuePolicyOption() != null) {
			options.put(PROP_SES_QUEUE_POLICY_OPTION, context.getQueuePolicyOption());
		}
		return options;
	}
	
	/**
	 * Create a {@link PushStreamContext} out of the options map 
	 * @param options the options map
	 * @return the context object
	 */
	public static <T> PushStreamContext<T> getPushStreamContext(Map<String, Object> options) {
		return new OptionPushStreamContext<T>(options);
	}
	
	/**
	 * Create a {@link SimplePushEventSourceContext} context out of the options map 
	 * @param options the options map
	 * @return context object
	 */
	public static <T> SimplePushEventSourceContext<T> getEventSourceContext(Map<String, Object> options) {
		return new OptionSimpleEventSourceContext<T>(options);
	}
	
	/**
	 * Creates a acknowledging event source for the class type
	 * @param type the class type 
	 * @param context the push stream context, can be <code>null</code>
	 * @return the instance of the {@link AcknowledgingEventSource}
	 */
	public static <T> AcknowledgingEventSource<T> fromClass(Class<T> type, PushStreamContext<T> context) {
		if (type == null) {
			throw new IllegalArgumentException("Class type parameter must not be null");
		}
		return new AcknowledgingEventSource<>(type, context); 
		
	}
	
	/**
	 * Creates a acknowledging event source for the simple push event source
	 * @param eventSource the simple push event source
	 * @param context the push stream context, can be <code>null</code>
	 * @return the instance of the {@link AcknowledgingEventSource}
	 */
	public static <T> AcknowledgingEventSource<T> fromSimpleEventSource(SimplePushEventSource<T> eventSource, PushStreamContext<T> context) {
		if (eventSource == null) {
			throw new IllegalArgumentException("Event source parameter must not be null");
		}
		return new AcknowledgingEventSource<T>(eventSource, context); 
	}
	
	/**
	 * Creates a {@link SimplePushEventSource} event source for the given type
	 * @param messageType the type of the message
	 * @param context the simple event source context stream context, can be <code>null</code>
	 * @return the instance of the {@link SimplePushEventSource}
	 */
	public static <T> SimplePushEventSource<T> createSimpleEventSource(Class<T> messageType, SimplePushEventSourceContext<T> context) {
		PushStreamProvider psp = new PushStreamProvider();
		BufferBuilder<SimplePushEventSource<T>, T, BlockingQueue<PushEvent<? extends T>>> builder = psp.buildSimpleEventSource(messageType);
		builder = configureEventSource(builder, context);
		return builder.build();
	}
	
	
	/**
	 * Configures an {@link BufferBuilder} with the given {@link PushStreamContext}
	 * @param builder the {@link BufferBuilder} instance, must not be <code>null</code>
	 * @param context the {@link PushStreamContext} with the configuration data
	 * @return {@link BufferBuilder} instance
	 */
	public static <T> BufferBuilder<SimplePushEventSource<T>, T, BlockingQueue<PushEvent<? extends T>>> configureEventSource(BufferBuilder<SimplePushEventSource<T>, T, BlockingQueue<PushEvent<? extends T>>> builder, SimplePushEventSourceContext<T> context) {
		if (builder == null) {
			throw new IllegalArgumentException("Cannot configure a push event builder from null instance");
		}
		if (context != null) {
			if (context.getBufferQueue() != null) {
				builder.withBuffer(context.getBufferQueue());
			}
			if (context.getBufferSize() > 0 && context.getBufferQueue() == null) {
				builder.withBuffer(new ArrayBlockingQueue<PushEvent<? extends T>>(context.getBufferSize()));
			}
			if (context.getQueuePolicy() != null) {
				builder.withQueuePolicy(context.getQueuePolicy());
			}
			if (context.getQueuePolicyOption() != null) {
				builder.withQueuePolicy(context.getQueuePolicyOption());
			}
			if (context.getQueuePolicyByName() != null) {
				builder.withQueuePolicy(context.getQueuePolicyByName());
			}
		}
		return builder;
	}

}
