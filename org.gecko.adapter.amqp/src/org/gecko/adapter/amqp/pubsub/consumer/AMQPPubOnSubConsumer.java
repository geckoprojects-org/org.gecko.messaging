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
package org.gecko.adapter.amqp.pubsub.consumer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.adapter.amqp.client.AMQPMessage;
import org.gecko.adapter.amqp.consumer.AMQPMessageImpl;
import org.gecko.util.pushstream.PushStreamContext;
import org.gecko.util.pushstream.PushStreamHelper;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamProvider;
import org.osgi.util.pushstream.SimplePushEventSource;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;

/**
 * Consumer class
 * @author Mark Hoffmann
 * @since 20.02.2018
 */
public class AMQPPubOnSubConsumer extends DefaultConsumer {

	private static final Logger logger = Logger.getLogger(AMQPPubOnSubConsumer.class.getName());
	private final Function<AMQPMessage, ByteBuffer> function;
	private final PushStreamProvider psp = new PushStreamProvider();
	private final SimplePushEventSource<AMQPMessage> eventSource = psp.buildSimpleEventSource(AMQPMessage.class).build();
	private PushStream<AMQPMessage> responseStream;

	/**
	 * Creates a new instance.
	 * @param channel
	 */
	public AMQPPubOnSubConsumer(Channel channel, Function<AMQPMessage, ByteBuffer> function, PushStreamContext<AMQPMessage> psCtx) {
		super(channel);
		this.function = function;
		responseStream = PushStreamHelper.createPushStream(eventSource, psCtx);
		responseStream.forEach(this::sendResponse);
	}
	
	/**
	 * Returns the eventSource.
	 * @return the eventSource
	 */
	public SimplePushEventSource<AMQPMessage> getEventSource() {
		return eventSource;
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
		AMQPMessageImpl requestMsg = new AMQPMessageImpl(exchange == null ? "" : exchange, ByteBuffer.wrap(body));
		requestMsg.setDeliveryTag(deliveryTag);
		requestMsg.setExchange(exchange);
		requestMsg.setRoutingKey(routingKey);
		requestMsg.setContentType(contentType);
		requestMsg.setReplyTo(replyTo);
		requestMsg.setCorrelationId(correlationId);
		try {
			if (eventSource.isConnected()) {
				eventSource.publish(requestMsg);
			}
		} catch(Exception ex){
			logger.log(Level.SEVERE, "Detected error on AMQP receive", ex);
		}
	}
	
	public void sendResponse(AMQPMessage message) {
		String replyTo = message.getReplyTo();
		String correlationId = message.getCorrelationId();
		long deliveryTag = message.getDeliveryTag();
		AMQP.BasicProperties replyProps = new AMQP.BasicProperties
                .Builder()
                .correlationId(correlationId).build();
		byte[] content = new byte[0];
		try {
			ByteBuffer response = function.apply(message);
			content = response.array();
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error executing function for correlation id " + correlationId, e);
		} finally {
			Channel channel = getChannel();
			try {
				channel.basicPublish("", replyTo, replyProps, content);
				channel.basicAck(deliveryTag, false);
			} catch (IOException e) {
				logger.log(Level.SEVERE, "Error sending answer for correlation id " + correlationId, e);
			}
		}
	}

	/**
	 * Closes the consumer
	 * @throws InterruptedException 
	 */
	public void close() throws InterruptedException {
		eventSource.close();
	}

}
