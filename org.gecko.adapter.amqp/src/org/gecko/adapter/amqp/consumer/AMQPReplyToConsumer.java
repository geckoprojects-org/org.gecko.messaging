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

package org.gecko.adapter.amqp.consumer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.osgi.messaging.Message;
import org.gecko.util.pushstream.SimplePushEventSourceContext;
import org.osgi.util.function.Predicate;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;

/**
 * Consumer class
 * @author Mark Hoffmann
 * @since 20.02.2018
 */
public class AMQPReplyToConsumer extends AMQPAcknowledgingConsumer {

	private static final Logger logger = Logger.getLogger(AMQPReplyToConsumer.class.getName());
	private final String correlationId;

	/**
	 * Creates a new instance.
	 * @param channel
	 */
	public AMQPReplyToConsumer(Channel channel, String topic, Predicate<Message> ackFilter, SimplePushEventSourceContext<Message> eventSourceContext, String correlationId) {
		super(channel, topic, ackFilter, eventSourceContext);
		this.correlationId = correlationId;
	}

	/* 
	 * (non-Javadoc)
	 * @see com.rabbitmq.client.DefaultConsumer#handleDelivery(java.lang.String, com.rabbitmq.client.Envelope, com.rabbitmq.client.AMQP.BasicProperties, byte[])
	 */
	@Override
	public void handleDelivery(String consumerTag, Envelope envelope, BasicProperties properties, byte[] body)
			throws IOException {
		String routingKey = envelope.getRoutingKey();
		String exchange = envelope.getExchange();
		long deliveryTag = envelope.getDeliveryTag();
		String contentType = properties.getContentType();
		// for RPC
		String correlationId = properties.getCorrelationId();
		String replyTo = properties.getReplyTo();
		AMQPMessageImpl msg = new AMQPMessageImpl(topic, ByteBuffer.wrap(body));
		msg.setDeliveryTag(deliveryTag);
		msg.setExchange(exchange);
		msg.setRoutingKey(routingKey);
		msg.setContentType(contentType);
		msg.setReplyTo(replyTo);
		msg.setCorrelationId(correlationId);
		if (correlationId == null || !correlationId.equals(this.correlationId)) {
			logger.log(Level.SEVERE, "This message does not fit to the correlation id, given by the request");
			return;
		}
		if (!eventSource.isConnected()) {
			CountDownLatch latch = new CountDownLatch(1);
			eventSource.connectPromise().thenAccept((v)->latch.countDown());
			try {
				latch.await();
			} catch (InterruptedException e) {
				logger.log(Level.SEVERE, "Waiting for a event source connection was interrupted", e);
			}
		}
		if (eventSource.isConnected()) {
			try {
				eventSource.publish(msg);
				logger.log(Level.INFO, "Received message: '" + new String(body) + "' with routingKey: " + routingKey
						+ ", contentType: " + contentType + ", deliveryTag: " + deliveryTag);
				logger.log(Level.INFO, "publish to event source for correlation: '" + correlationId);
				if (mbean != null) {
					mbean.setLastMessageTime(new Date());
				}
			} catch (Exception ex) {
				logger.log(Level.SEVERE, "Detected error on AMQP receive", ex);
			}
		} else {
			logger.severe("Event source is not connected");
		}
	}
	
	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.consumer.AMQPAcknowledgingConsumer#close()
	 */
	@Override
	public void close() {
		logger.log(Level.INFO, "clode event source");
		super.close();
	}

}
