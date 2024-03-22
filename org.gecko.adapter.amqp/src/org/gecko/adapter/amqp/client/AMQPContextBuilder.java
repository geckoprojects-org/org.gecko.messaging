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
package org.gecko.adapter.amqp.client;

import static java.util.Objects.isNull;
import static org.gecko.adapter.amqp.consumer.AMQPHelper.validateExchangeConfiguration;
import static org.gecko.adapter.amqp.consumer.AMQPHelper.validateQueueConfiguration;

import java.util.Date;

import org.gecko.adapter.amqp.api.AMQPConfiguration;
import org.gecko.adapter.amqp.api.AMQPProperties;
import org.gecko.adapter.amqp.client.AMQPContext.RoutingType;
import org.gecko.osgi.messaging.SimpleMessagingContextBuilder;

/**
 * Builder for the AMQP MessageContext
 * @author Mark Hoffmann
 * @since 10.10.2017
 */
public class AMQPContextBuilder extends SimpleMessagingContextBuilder {
	
	private AMQPContext context = new AMQPContext();
	
	public static AMQPContextBuilder createBuilder(AMQPConfiguration configuration) {
		if (isNull(configuration)) {
			return new AMQPContextBuilder();
		}
		AMQPContextBuilder builder = new AMQPContextBuilder();
		if (validateExchangeConfiguration(configuration)) {
			builder = builder.durable().exchange(configuration.exchange(), configuration.routingKey());
		}
		if (validateQueueConfiguration(configuration)) {
			if (validateExchangeConfiguration(configuration)) {
				builder = builder.queue(configuration.topic());
			} else {
				builder = builder.topic().durable().queue(configuration.topic());
			}
		}
		return builder;
	}
	
	/* 
	 * (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessageContextBuilder#build()
	 */
	@Override
	public AMQPContext build() {
		return buildContext(context);
	}
	
	public AMQPContextBuilder properties(AMQPProperties props) {
		context.setProperties(props);
		return this;
	}
	
	public AMQPContextBuilder durable() {
		context.setDurable(true);
		return this;
	}
	
	public AMQPContextBuilder queue(String queueName) {
		context.setQueueName(queueName);
		context.setQueueMode(true);
		return this;
	}
	
	public AMQPContextBuilder exchange(String exchangeName, String routingKey) {
		context.setExchangeName(exchangeName);
		context.setRoutingKey(routingKey);
		context.setExchangeMode(true);
		return this;
	}
	
	public AMQPContextBuilder appId(String appId) {
		context.setAppId(appId);
		return this;
	}
	
	public AMQPContextBuilder clusterId(String clusterId) {
		context.setClusterId(clusterId);
		return this;
	}
	
	public AMQPContextBuilder direct() {
		context.setRoutingType(RoutingType.DIRECT);
		return this;
	}
	
	public AMQPContextBuilder topic() {
		context.setRoutingType(RoutingType.TOPIC);
		return this;
	}
	
	public AMQPContextBuilder fanout() {
		context.setRoutingType(RoutingType.FANOUT);
		return this;
	}
	
	public AMQPContextBuilder header() {
		context.setRoutingType(RoutingType.HEADER);
		return this;
	}
	
	public AMQPContextBuilder exclusive() {
		context.setExclusive(true);
		return this;
	}
	
	public AMQPContextBuilder autoDelete() {
		context.setAutoDelete(true);
		return this;
	}
	
	public AMQPContextBuilder autoAcknowledge() {
		context.setAutoAcknowledge(true);
		return this;
	}
	
	public AMQPContextBuilder asRPCRequest() {
		context.setRpc(true);
		return this;
	}
	
	public AMQPContextBuilder asRPCRequest(String correlationId, String replyTo) {
		if (correlationId == null || replyTo == null) {
			throw new IllegalArgumentException("One of the RPC argumens are null. Both parameter are mendatory for RPC requests");
		}
		context.setReplyAddress(replyTo);
		context.setCorrelationId(correlationId);
		context.setRpc(true);
		return this;
	}
	
	public AMQPContextBuilder asRPCResponse(String correlationId) {
		if (correlationId == null) {
			throw new IllegalArgumentException("At lease the correlation it must be set for a RPC response");
		}
		context.setCorrelationId(correlationId);
		context.setRpc(true);
		return this;
	}
	
	public AMQPContextBuilder expiration(String expiration) {
		if (expiration != null) {
			context.setExpiration(expiration);
		}
		return this;
	}
	
	public AMQPContextBuilder messageId(String messageId) {
		if (messageId != null) {
			context.setMessageId(messageId);
		}
		return this;
	}
	
	public AMQPContextBuilder userId(String userId) {
		if (userId != null) {
			context.setUserId(userId);
		}
		return this;
	}
	
	public AMQPContextBuilder timestamp(Date timestamp) {
		if (timestamp != null) {
			context.setTimestamp(timestamp);
		}
		return this;
	}
	
	public AMQPContextBuilder priority(Integer priority) {
		if (priority != null) {
			context.setPriority(priority);
		}
		return this;
	}
	
	public AMQPContextBuilder deliveryMode(Integer deliveryMode) {
		if (deliveryMode != null) {
			context.setDeliveryMode(deliveryMode);
		}
		return this;
	}
	
	

}
