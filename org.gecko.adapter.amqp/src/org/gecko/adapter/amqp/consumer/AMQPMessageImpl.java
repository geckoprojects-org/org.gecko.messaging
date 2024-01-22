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

import java.nio.ByteBuffer;

import org.gecko.adapter.amqp.client.AMQPMessage;
import org.gecko.osgi.messaging.SimpleMessage;
import org.gecko.osgi.messaging.SimpleMessagingContext;

/**
 * Implementation of a AMQP message
 * @author Mark Hoffmann
 * @since 10.12.2018
 */
public class AMQPMessageImpl extends SimpleMessage implements AMQPMessage {
	
	private String exchange;
	private String routingKey;
	private String contentType;
	private String replyTo;
	private String correlationId;
	private long deliveryTag;
	
	/**
	 * Creates a new instance.
	 */
	public AMQPMessageImpl(String topic, ByteBuffer content) {
		super(topic, content);
	}
	
	/**
	 * Creates a new instance.
	 */
	public AMQPMessageImpl(String topic, ByteBuffer content, SimpleMessagingContext context) {
		super(topic, content, context);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.client.AMQPMessage#getExchange()
	 */
	public String getExchange() {
		return exchange;
	}

	/**
	 * Sets the exchange.
	 * @param exchange the exchange to set
	 */
	public void setExchange(String exchange) {
		this.exchange = exchange;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.client.AMQPMessage#getRoutingKey()
	 */
	public String getRoutingKey() {
		return routingKey;
	}

	/**
	 * Sets the routingKey.
	 * @param routingKey the routingKey to set
	 */
	public void setRoutingKey(String routingKey) {
		this.routingKey = routingKey;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.client.AMQPMessage#getDeliveryTag()
	 */
	public long getDeliveryTag() {
		return deliveryTag;
	}

	/**
	 * Sets the deliveryTag.
	 * @param deliveryTag the deliveryTag to set
	 */
	public void setDeliveryTag(long deliveryTag) {
		this.deliveryTag = deliveryTag;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.client.AMQPMessage#getContentType()
	 */
	public String getContentType() {
		return contentType;
	}

	/**
	 * Sets the contentType.
	 * @param contentType the contentType to set
	 */
	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.client.AMQPMessage#getReplyTo()
	 */
	public String getReplyTo() {
		return replyTo;
	}

	/**
	 * Sets the replyTo.
	 * @param replyTo the replyTo to set
	 */
	public void setReplyTo(String replyTo) {
		this.replyTo = replyTo;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.client.AMQPMessage#getCorrelationId()
	 */
	public String getCorrelationId() {
		return correlationId;
	}

	/**
	 * Sets the correlationId.
	 * @param correlationId the correlationId to set
	 */
	public void setCorrelationId(String correlationId) {
		this.correlationId = correlationId;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.client.AMQPMessage#isRPC()
	 */
	@Override
	public boolean isRPC() {
		return getCorrelationId() != null && getReplyTo() != null;
	}

}
