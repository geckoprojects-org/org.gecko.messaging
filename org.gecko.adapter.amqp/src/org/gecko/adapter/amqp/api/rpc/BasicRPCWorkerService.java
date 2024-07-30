/*
 * Copyright (c) 2012 - 2024 Data In Motion and others.
 * All rights reserved. 
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Data In Motion - initial API and implementation
 */

package org.gecko.adapter.amqp.api.rpc;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.gecko.adapter.amqp.api.AMQPConfiguration;
import org.gecko.adapter.amqp.api.BasicReSubscribeConsumer;
import org.gecko.adapter.amqp.api.BasicReSubscriber;
import org.gecko.adapter.amqp.api.WorkerFunction;
import org.gecko.adapter.amqp.client.AMQPContext;
import org.gecko.util.common.concurrent.NamedThreadFactory;
import org.osgi.service.component.ComponentServiceObjects;
import org.osgi.util.promise.PromiseFactory;

import com.rabbitmq.client.Channel;

/**
 * Default implementation of the messaging service for the AMQP protocol, using the RabbitMQ AMQP client
 * @see https://www.rabbitmq.com/api-guide.html
 * @author Mark Hoffmann
 * @since 01.03.2024
 */
public abstract class BasicRPCWorkerService extends BasicReSubscriber<Void> implements MessagingResubscribingPubOnSub {

	protected PromiseFactory pf;

	public void activate(AMQPConfiguration config, Map<String, Object> properties) throws Exception {
		super.activate(config, properties);
		ThreadFactory tf = NamedThreadFactory.newNamedFactory(String.format("RPCWorker-%s", config.name()));
		pf = new PromiseFactory(Executors.newCachedThreadPool(tf));
	}
	
	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.api.BasicReSubscriber#createReSubscribeConsumer(com.rabbitmq.client.Channel, java.lang.String, org.gecko.adapter.amqp.client.AMQPContext)
	 */
	@Override
	protected BasicReSubscribeConsumer<Void> createReSubscribeConsumer(Channel channel, String consumerTag,
			AMQPContext context) {
		BasicRPCWorkerConsumer consumer = new BasicRPCWorkerConsumer(channel, consumerTag, context, getWorkerFunction(), pf);
		consumer.setMaxWorkerCount(getAmqpProperties().maxActivateRPCWorker());
		return consumer;
	}

	/**
	 * Returns the workerFunction.
	 * @return the workerFunction
	 */
	public abstract ComponentServiceObjects<WorkerFunction> getWorkerFunction();
	
}
