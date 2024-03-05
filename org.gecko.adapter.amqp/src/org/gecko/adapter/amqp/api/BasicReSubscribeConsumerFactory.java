/**
 * Copyright (c) 2012 - 2024 Data In Motion and others.
 * All rights reserved. 
 * 
 * This program and the accompanying materials are made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
package org.gecko.adapter.amqp.api;

import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.Map;

import org.gecko.adapter.amqp.client.AMQPContext;
import org.osgi.annotation.versioning.ProviderType;
import org.osgi.service.component.ComponentServiceObjects;
import org.osgi.util.promise.PromiseFactory;

import com.rabbitmq.client.Channel;

/**
 * Factory to create re-subscribing consumer instances 
 * @author Mark Hoffmann
 * @since 04.03.2024
 */
@ProviderType
public interface BasicReSubscribeConsumerFactory<T> {
	
	interface ConsumerFactoryContext {
		Channel getChannel();
		String getConsumerTag();
		AMQPContext getContext();
		ComponentServiceObjects<WorkerFunction> getWorkerFuction();
		PromiseFactory getPromiseFactory();
		Map<String, Object> getProperties();
		
	}
	
	public static ConsumerFactoryContext createContext(Channel channel, String tag, AMQPContext context, ComponentServiceObjects<WorkerFunction> workerCSO, PromiseFactory pf, Map<String, Object> properties) {
		return new ConsumerFactoryContext() {
			
			@Override
			public ComponentServiceObjects<WorkerFunction> getWorkerFuction() {
				return requireNonNull(workerCSO);
			}
			
			@Override
			public Map<String, Object> getProperties() {
				return nonNull(properties) ? properties : Collections.emptyMap();
			}
			
			@Override
			public PromiseFactory getPromiseFactory() {
				return pf;
			}
			
			@Override
			public AMQPContext getContext() {
				requireNonNull(context);
				return context;
			}
			
			@Override
			public String getConsumerTag() {
				requireNonNull(tag);
				return tag;
			}
			
			@Override
			public Channel getChannel() {
				requireNonNull(channel);
				return channel;
			}
		};
	}
	
	/**
	 * Factory method to create a re-subscribing consumer
	 * @param context the factory context
	 * @return a {@link BasicReSubscribeConsumer} instance
	 */
	public BasicReSubscribeConsumer<T> createConsumer(ConsumerFactoryContext context);

}
