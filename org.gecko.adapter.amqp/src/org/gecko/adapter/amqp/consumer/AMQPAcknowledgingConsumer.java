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
package org.gecko.adapter.amqp.consumer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.adapter.amqp.client.AMQPMessage;
import org.gecko.adapter.amqp.jmx.AMQPConsumerMetric;
import org.gecko.osgi.messaging.Message;
import org.gecko.osgi.messaging.MessagingContext;
import org.gecko.util.pushstream.PushStreamHelper;
import org.gecko.util.pushstream.SimplePushEventSourceContext;
import org.gecko.util.pushstream.source.AcknowledgingEventSource;
import org.osgi.util.function.Predicate;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamBuilder;
import org.osgi.util.pushstream.SimplePushEventSource;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;

/**
 * Consumer class
 * 
 * @author Mark Hoffmann
 * @since 20.02.2018
 */
public class AMQPAcknowledgingConsumer extends DefaultConsumer {

	private static final Logger logger = Logger.getLogger(AMQPAcknowledgingConsumer.class.getName());
	protected final AcknowledgingEventSource<Message> eventSource;
	protected final String topic;
	protected AMQPConsumerMetric mbean;
	
	/**
	 * Creates a new instance.
	 * 
	 * @param channel
	 */
	public AMQPAcknowledgingConsumer(Channel channel, String topic, Predicate<Message> ackFilter, SimplePushEventSourceContext<Message> eventSourceContext) {
		super(channel);
		SimplePushEventSource<Message> spes = PushStreamHelper.createSimpleEventSource(Message.class, eventSourceContext);
		this.eventSource = PushStreamHelper
				.fromSimpleEventSource(spes, null).acknowledgeFilter(ackFilter)
				.acknowledge(this::acknowledgeMessage).negativeAcknowledge(this::rejectMessage);
		this.topic = topic;
	}

	/**
	 * Creates a new instance.
	 * 
	 * @param channel
	 */
	public AMQPAcknowledgingConsumer(Channel channel, String topic, SimplePushEventSource<Message> eventSource, Predicate<Message> ackFilter) {
		super(channel);
		this.eventSource = PushStreamHelper
				.fromSimpleEventSource(eventSource, null).acknowledgeFilter(ackFilter)
				.acknowledge(this::acknowledgeMessage).negativeAcknowledge(this::rejectMessage);
		this.topic = topic;
	}

	public PushStream<Message> createPushstream(MessagingContext context) {
		PushStreamBuilder<Message, BlockingQueue<PushEvent<? extends Message>>> buildStream = PushStreamHelper
				.configurePushStreamBuilder(eventSource, context);
		return buildStream.build();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.rabbitmq.client.DefaultConsumer#handleDelivery(java.lang.String,
	 * com.rabbitmq.client.Envelope, com.rabbitmq.client.AMQP.BasicProperties,
	 * byte[])
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
		logger.log(Level.FINE, "Received message: '" + new String(body) + "' with routingKey: " + routingKey
				+ ", contentType: " + contentType + ", deliveryTag: " + deliveryTag);
		AMQPMessageImpl msg = new AMQPMessageImpl(topic, ByteBuffer.wrap(body));
		msg.setDeliveryTag(deliveryTag);
		msg.setExchange(exchange);
		msg.setRoutingKey(routingKey);
		msg.setContentType(contentType);
		msg.setReplyTo(replyTo);
		msg.setCorrelationId(correlationId);
		if (eventSource.isConnected()) {
			try {
				eventSource.publish(msg);
				if (mbean != null) {
					mbean.setLastMessageTime(new Date());
				}
			} catch (Exception ex) {
				logger.log(Level.SEVERE, "Detected error on AMQP receive", ex);
			}
			
		}
	}

	protected void acknowledgeMessage(Message message) {
		AMQPMessage am = (AMQPMessage) message;
		long deliveryTag = am.getDeliveryTag();
		logger.log(Level.FINEST, "Acknowledge message with deliveryTag: " + deliveryTag);
		try {
			getChannel().basicAck(deliveryTag, false);
		} catch (IOException e) {
			logger.log(Level.SEVERE, "Detected error on acknowledging the message ", e);
		}
	}

	protected void rejectMessage(Message message) {
		AMQPMessage am = (AMQPMessage) message;
		long deliveryTag = am.getDeliveryTag();
		logger.log(Level.FINEST, "Reject message with deliveryTag: " + deliveryTag);
		try {
			getChannel().basicReject(deliveryTag, false);
		} catch (IOException e) {
			logger.log(Level.SEVERE, "Detected error on acknowledging the message ", e);
		}
	}
	
	public void close() {
		eventSource.close();
	}

	/**
	 * Returns the mbean.
	 * @return the mbean
	 */
	public AMQPConsumerMetric getMBean() {
		return mbean;
	}

	/**
	 * Sets the mbean.
	 * @param mbean the mbean to set
	 */
	public void setMBean(AMQPConsumerMetric mbean) {
		this.mbean = mbean;
	}

}
