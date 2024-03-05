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
package org.gecko.adapter.amqp.pubsub;

import static java.util.Objects.requireNonNull;

import java.util.Map;

import org.gecko.adapter.amqp.api.AMQPConfiguration;
import org.gecko.adapter.amqp.api.BasicReSubscribeConsumer;
import org.gecko.adapter.amqp.api.BasicReSubscribeConsumerFactory;
import org.gecko.adapter.amqp.api.BasicReSubscribeConsumerFactory.ConsumerFactoryContext;
import org.gecko.adapter.amqp.api.WorkerFunction;
import org.gecko.adapter.amqp.api.rpc.BasicRPCWorkerService;
import org.gecko.adapter.amqp.api.rpc.MessagingResubscribingPubOnSub;
import org.gecko.adapter.amqp.client.AMQPContext;
import org.osgi.annotation.bundle.Capability;
import org.osgi.service.component.ComponentServiceObjects;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceScope;
import org.osgi.util.promise.Promise;

import com.rabbitmq.client.Channel;

/**
 * Implementation of the messaging service for the AMQP protocol, using the RabbitMQ AMQP client
 * @see https://www.rabbitmq.com/api-guide.html
 * @author Mark Hoffmann
 * @since 01.03.2024
 */
@Capability(namespace="gecko.messaging", name="rpcWorker", version="1.0.0", attribute= {"vendor=Gecko.io", "implementation=AMQP"})
@Component(name="AMQPRPCWorkerService", service = MessagingResubscribingPubOnSub.class, configurationPolicy=ConfigurationPolicy.REQUIRE)
public class AMQPRPCWorkerService extends BasicRPCWorkerService {

	@Reference(name="rpc.workerFunction", scope = ReferenceScope.PROTOTYPE_REQUIRED)
	private ComponentServiceObjects<WorkerFunction> workerFunction;
	
	@Reference(name="rpc.consumerFactory")
	private BasicReSubscribeConsumerFactory<Void> consumerFactory;

	@Activate
	public void activate(AMQPConfiguration config, Map<String, Object> properties) throws Exception {
		super.activate(config, properties);
		
	}
	
	@Deactivate
	public void deactivate() throws Exception {
		super.close();
	}

	/**
	 * Creates the {@link BasicReSubscribeConsumer} and pre-configures it 
	 * @param channel the {@link Channel}
	 * @param consumerTag the consumer identifier
	 * @param context the {@link AMQPContext}
	 * @return the consumer instance
	 */
	protected BasicReSubscribeConsumer<Void> createReSubscribeConsumer(Channel channel, String consumerTag, AMQPContext context) {
		requireNonNull(getConsumerFactory());
		ConsumerFactoryContext cfc = BasicReSubscribeConsumerFactory.createContext(channel, 
				consumerTag, 
				context, 
				getWorkerFunction(),  
				pf, 
				null);
		return getConsumerFactory().createConsumer(cfc);
	}

	/**
	 * Returns the workerFunction.
	 * @return the workerFunction
	 */
	public ComponentServiceObjects<WorkerFunction> getWorkerFunction() {
		return workerFunction;
	}
	
	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.api.BasicReSubscriber#setConsumerFactory(org.gecko.adapter.amqp.api.BasicReSubscribeConsumerFactory)
	 */
	@Override
	@Reference
	public void setConsumerFactory(BasicReSubscribeConsumerFactory<Void> consumerFactory) {
		this.consumerFactory = consumerFactory;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.api.rpc.BasicRPCWorkerService#getConsumerFactory()
	 */
	@Override
	public BasicReSubscribeConsumerFactory<Void> getConsumerFactory() {
		return consumerFactory;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.api.rpc.MessagingResubscribingPubOnSub#resubscribe(java.lang.String)
	 */
	@Override
	public void resubscribe(String topic) {
		Promise<Void> promise = subscribePromise(topic);
		promise.onResolve(()->resubscribe(topic));
	}

}
