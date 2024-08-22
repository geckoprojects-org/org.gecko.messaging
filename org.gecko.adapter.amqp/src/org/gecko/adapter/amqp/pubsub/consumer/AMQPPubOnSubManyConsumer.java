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

package org.gecko.adapter.amqp.pubsub.consumer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.adapter.amqp.client.AMQPMessage;
import org.gecko.adapter.amqp.consumer.AMQPMessageImpl;
import org.gecko.osgi.messaging.ReplyToPolicy;
import org.gecko.osgi.messaging.SimpleMessagingContext;
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
public class AMQPPubOnSubManyConsumer extends DefaultConsumer {

	private static final Logger logger = Logger.getLogger(AMQPPubOnSubManyConsumer.class.getName());
	private final Function<AMQPMessage, PushStream<ByteBuffer>> function;
	private final PushStreamProvider psp = new PushStreamProvider();
	private final SimplePushEventSource<AMQPMessage> eventSource = psp.buildSimpleEventSource(AMQPMessage.class).build();
	private PushStream<AMQPMessage> responseStream;
	
	static class POSContext {
		
		private AMQPMessage message;
		private ByteBuffer buffer;
		
		/**
		 * Creates a new instance.
		 */
		public POSContext(AMQPMessage message, ByteBuffer buffer) {
			this.message = message;
			this.buffer = buffer;
		}
		
		public byte[] getContent() {
			return buffer == null ? new byte[0] : buffer.array();
		}
		
		public String getReplyTo() {
			return message.getReplyTo();
		}
		
		public String getCorrelationId() {
			return message.getCorrelationId();
		}
		
		public long getDeliveryTag() {
			return message.getDeliveryTag();
		}
		
		public BasicProperties getBasicProperties() {
			return new AMQP.BasicProperties
					.Builder()
					.correlationId(getCorrelationId()).build();
		}
		
	}
	
	/**
	 * Creates a new instance.
	 * @param channel
	 */
	public AMQPPubOnSubManyConsumer(Channel channel, Function<AMQPMessage, PushStream<ByteBuffer>> function, PushStreamContext<AMQPMessage> psCtx) {
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
		ReplyToPolicy policy = ReplyToPolicy.getPolicy(properties.getHeaders());
		// for RPC
		String correlationId = properties.getCorrelationId();
		String replyTo = properties.getReplyTo();
		
		SimpleMessagingContext smc = new SimpleMessagingContext();
		smc.setReplyToPolicy(policy);
		AMQPMessageImpl requestMsg = new AMQPMessageImpl(exchange == null ? "" : exchange, ByteBuffer.wrap(body), smc);
		requestMsg.setDeliveryTag(deliveryTag);
		requestMsg.setExchange(exchange);
		requestMsg.setRoutingKey(routingKey);
		requestMsg.setContentType(contentType);
		requestMsg.setReplyTo(replyTo);
		requestMsg.setCorrelationId(correlationId);
		try {
			if (eventSource.isConnected()) {
				eventSource.publish(requestMsg);
				getChannel().basicAck(deliveryTag, false);
			}
		} catch(Exception ex){
			logger.log(Level.SEVERE, "Detected error on AMQP receive", ex);
		}
	}
	
	public void sendResponse(AMQPMessage message) {
		try {
			PushStream<ByteBuffer> response = function.apply(message);
			PushStream<POSContext> stream = response.map(b->new POSContext(message, b));
			switch (message.getContext().getReplyPolicy()) {
			case SINGLE:
				stream.findFirst().thenAccept(o->o.ifPresent(this::publishResponse));
				stream.close();
				break;
			case MULTIPLE:
				stream.forEach(this::publishResponse);
				break;
			default:
				break;
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error executing function for correlation id " + message.getCorrelationId(), e);
		}
	}
	

	private void publishResponse(POSContext context) {
		byte[] content = context.getContent();
		Channel channel = getChannel();
		try {
			channel.basicPublish("", context.getReplyTo(), context.getBasicProperties(), content);
		} catch (IOException e) {
			logger.log(Level.SEVERE, "Error sending answer for correlation id " + context.getCorrelationId(), e);
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
