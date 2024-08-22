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

package org.gecko.adapter.amqp.api;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.adapter.amqp.api.BasicReSubscribeConsumerFactory.ConsumerFactoryContext;
import org.gecko.adapter.amqp.client.AMQPContext;
import org.gecko.adapter.amqp.client.AMQPContextBuilder;
import org.gecko.util.common.concurrent.NamedThreadFactory;
import org.osgi.util.promise.Deferred;
import org.osgi.util.promise.Promise;
import org.osgi.util.promise.PromiseFactory;

import com.rabbitmq.client.Channel;

/**
 * Basic AMQP service implementation that can be extended to create connections based on an {@link AMQPConfiguration}
 * @author Mark Hoffmann
 * @since 26.02.2024
 */
public abstract class BasicReSubscriber<T> extends BasicAMQPService {

	private static final Logger logger = Logger.getLogger(BasicReSubscriber.class.getName());
	protected PromiseFactory pf = new PromiseFactory(Executors.newCachedThreadPool(NamedThreadFactory.newNamedFactory("AMQPRe-Subscriber")));
	private BasicReSubscribeConsumerFactory<T> consumerFactory;

	/**
	 * subscribes to a topic and returns the subscription promise
	 * @param topic the topic to subscribe upon
	 * @return the promise the resolved on re-subscription
	 */
	public Promise<T> subscribePromise(String topic) {
		AMQPConfiguration configuration = getConfiguration();
		AMQPProperties amqpProps = getAmqpProperties();
		requireNonNull(amqpProps);

		AMQPContextBuilder ctxBuilder = AMQPContextBuilder.createBuilder(configuration);
		AMQPContext ctx = ctxBuilder.properties(amqpProps).durable().queue(topic).build();

		Deferred<T> deferred = pf.deferred();

		try {
			// create channel
			Channel channel = connectExchange(ctx, true);
			// set default message fetch size for channel
			if (amqpProps.basicQos() != 0 && amqpProps.basicQos() > 0) {
				channel.basicQos(amqpProps.basicQos());
			}

			final String consumerTag = UUID.randomUUID().toString();
			// create DefaultConsumer that handles  
			BasicReSubscribeConsumer<T> consumer = createReSubscribeConsumer(channel, consumerTag, ctx);
			requireNonNull(consumer);
			consumer.setDeferred(deferred);
			consumer.setDisconnectCallback(this::doDisconnectChannel);

			if (channel.isOpen()) {
				channel.basicConsume(topic, amqpProps.autoAcknowledge(), consumerTag, consumer);
			} else {
				deferred.fail(new IllegalStateException("The channel is not open"));
			}
		} catch (IOException | TimeoutException e) {
			doDisconnectChannel(ctx);
			deferred.fail(e);
		} 
		return deferred.getPromise();
	}
	
	/**
	 * Creates the {@link BasicReSubscribeConsumer} and pre-configures it 
	 * @param channel the {@link Channel}
	 * @param consumerTag the consumer identifier
	 * @param context the {@link AMQPContext}
	 * @return the consumer instance
	 */
	protected BasicReSubscribeConsumer<T> createReSubscribeConsumer(Channel channel, String consumerTag, AMQPContext context) {
		requireNonNull(getConsumerFactory());
		ConsumerFactoryContext cfc = BasicReSubscribeConsumerFactory.createContext(channel, consumerTag, context, null,  pf, null);
		return getConsumerFactory().createConsumer(cfc);
	}

	/**
	 * Executes a disconnect for the channel that belongs to the context
	 */
	protected void doDisconnectChannel(AMQPContext context) {
		try {
			disconnectChannel(context, true);
		} catch (IOException | TimeoutException e) {
			logger.log(Level.SEVERE, e, ()->"Error disconnecting channel");
		}
	}

	/**
	 * Returns the consumerFactory.
	 * @return the consumerFactory
	 */
	public BasicReSubscribeConsumerFactory<T> getConsumerFactory() {
		return consumerFactory;
	}

	/**
	 * Sets the consumerFactory.
	 * @param consumerFactory the consumerFactory to set
	 */
	public void setConsumerFactory(BasicReSubscribeConsumerFactory<T> consumerFactory) {
		requireNonNull(consumerFactory);
		this.consumerFactory = consumerFactory;
	}

}
