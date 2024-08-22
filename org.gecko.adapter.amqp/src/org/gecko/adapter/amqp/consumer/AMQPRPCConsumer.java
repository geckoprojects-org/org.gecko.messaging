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
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.adapter.amqp.client.AMQPMessage;
import org.gecko.osgi.messaging.Message;
import org.osgi.util.promise.Deferred;
import org.osgi.util.promise.Promise;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;

/**
 * Consumer class
 * @author Mark Hoffmann
 * @since 20.02.2018
 */
public class AMQPRPCConsumer extends DefaultConsumer {

	private static final Logger logger = Logger.getLogger(AMQPRPCConsumer.class.getName());
	private final String correlationId;
	private final Deferred<AMQPMessage> messageDeferred = new Deferred<AMQPMessage>();
	private Promise<AMQPMessage> message = messageDeferred.getPromise();

	/**
	 * Creates a new instance.
	 * @param channel
	 */
	public AMQPRPCConsumer(Channel channel, String correlationId) {
		super(channel);
		this.correlationId = correlationId;
	}

	/**
	 * Returns the result blocking
	 * @return
	 */
	public Promise<Message> getMessage() {
		return message.map(m->(Message)m);
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
		String messageId = properties.getMessageId();
		// for RPC
		String correlationId = properties.getCorrelationId();
		if (correlationId == null || !correlationId.equals(this.correlationId)) {
			logger.log(Level.SEVERE, "This message does not fit to the correlation id, given by the request");
			return;
		}
		String replyTo = properties.getReplyTo();
		logger.log(Level.FINE, "Received message: '" + new String(body) + "' with routingKey: " + routingKey + ", contentType: " + contentType + ", deliveryTag: " + deliveryTag);
		try {
			AMQPMessageImpl msg = new AMQPMessageImpl(exchange == null ? "" : exchange, ByteBuffer.wrap(body));
			msg.setDeliveryTag(deliveryTag);
			msg.setExchange(exchange);
			msg.setRoutingKey(routingKey);
			msg.setContentType(contentType);
			msg.setReplyTo(replyTo);
			msg.setCorrelationId(correlationId);
			msg.setMessageId(messageId);
			messageDeferred.resolve(msg);
		} catch(Exception ex){
			logger.log(Level.SEVERE, "Detected error on AMQP receive", ex);
		}
	}

}
