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

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNull;

import java.nio.ByteBuffer;

import org.gecko.adapter.amqp.api.AMQPConfiguration;
import org.gecko.adapter.amqp.client.AMQPContext;
import org.gecko.adapter.amqp.client.AMQPMessage;

import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.AMQP.BasicProperties;

/**
 * Helper class for the AMQP stuff
 * @author Mark Hoffmann
 * @since 12.12.2018
 */
public class AMQPHelper {

	/**
	 * Returns a key from the given context. This key can be used for channels, consumers
	 * @param context the {@link AMQPContext} instance, must not be <code>null</code>
	 * @return the key as string
	 */
	public static String getKey(AMQPContext context) {
		if (context == null) {
			throw new IllegalArgumentException("Error creating key. The parameter context must not be null");
		}
		String key = context.isRpc() ? context.getMessageId() : isNull(context.getQueueName()) ? "" : context.getQueueName() + "_";
		if (context.isExchangeMode()) {
			String exchange = context.getExchangeName();
			String routingKey = context.getRoutingKey();
			String routingType = context.getRoutingType();
			key += exchange + "_" + routingKey + "_" + routingType;
		}
		return key;
	}
	
	/**
	 * Returns a key from the given context. This key can be used for channels, consumers
	 * @param context the {@link AMQPContext} instance, must not be <code>null</code>
	 * @param subscribe <code>true</code> for subscription channel
	 * @return the key as string
	 */
	public static String getKey(AMQPContext context, boolean subscribe) {
		String key = AMQPHelper.getKey(context);
		if (subscribe) {
			key += "_sub";
		}
		return key;
	}

	/**
	 * Creates the message properties from the context object
	 * @param ctx the AMQP context instance, must not be <code>null</code> 
	 * @return the properties instance
	 */
	public static BasicProperties createMessageProperties(AMQPContext ctx) {
		BasicProperties.Builder builder = new BasicProperties.Builder();
		if (ctx.getCorrelationId() != null) {
			builder.correlationId(ctx.getCorrelationId());
		}
		if (ctx.getReplyAddress() != null) {
			builder.replyTo(ctx.getReplyAddress());
		}
		if (ctx.getAppId() != null) {
			builder.appId(ctx.getAppId());
		}
		if (ctx.getClusterId() != null) {
			builder.clusterId(ctx.getClusterId());
		}
		if (ctx.getContentEncoding() != null) {
			builder.contentEncoding(ctx.getContentEncoding());
		}
		if (ctx.getContentType() != null) {
			builder.contentType(ctx.getContentType());
		}
		if (ctx.getDeliveryMode() != null) {
			builder.deliveryMode(ctx.getDeliveryMode());
		}
		if (ctx.getPriority() != null) {
			builder.priority(ctx.getPriority());
		}
		if (ctx.getExpiration() != null) {
			builder.expiration(ctx.getExpiration());
		}
		if (ctx.getMessageId() != null) {
			builder.messageId(ctx.getMessageId());
		}
		if (ctx.getTimestamp() != null) {
			builder.timestamp(ctx.getTimestamp());
		}
		if (ctx.getUserId() != null) {
			builder.userId(ctx.getUserId());
		}
		if (!ctx.getHeader().isEmpty()) {
			builder.headers(ctx.getHeader());
		}
		return builder.build();
	}

	/**
	 * Verifies if a exchange and routing key is provided in the configuration. This is at least needed for
	 * exchange mode 
	 * @param configuration the configuration
	 * @return
	 */
	public static boolean validateExchangeConfiguration(AMQPConfiguration configuration) {
		return nonNull(configuration) &&
				nonNull(configuration.routingKey()) && !configuration.routingKey().isBlank() && 
				nonNull(configuration.exchange()) && !configuration.exchange().isBlank();
	}

	/**
	 * Verifies if a exchange and routing key is provided in the configuration. This is at least needed for
	 * exchange mode 
	 * @param configuration the configuration
	 * @return
	 */
	public static boolean validateExchangeContext(AMQPContext context) {
		return nonNull(context) &&
				nonNull(context.getRoutingKey()) && !context.getRoutingKey().isBlank() && 
				nonNull(context.getExchangeName()) && !context.getExchangeName().isBlank();
	}

	public static boolean validateQueueConfiguration(AMQPConfiguration configuration) {
		return nonNull(configuration) &&
				nonNull(configuration.topic()) && ! configuration.topic().isBlank();
	}

	public static boolean validateQueueContext(AMQPContext context) {
		return nonNull(context) &&
				nonNull(context.getQueueName()) && ! context.getQueueName().isBlank();
	}
	
	public static AMQPMessageImpl createMessage(Envelope envelope, com.rabbitmq.client.BasicProperties properties, ByteBuffer buffer) {
		requireNonNull(envelope);
		requireNonNull(properties);
		requireNonNull(buffer);
		String routingKey = envelope.getRoutingKey();
		String exchange = envelope.getExchange();
		long deliveryTag = envelope.getDeliveryTag();
		String contentType = properties.getContentType();
		// for RPC
		String correlationId = properties.getCorrelationId();
		String replyTo = properties.getReplyTo();
		String messageId = properties.getMessageId();
		AMQPMessageImpl message = new AMQPMessageImpl(exchange == null ? "" : exchange, buffer);
		message.setDeliveryTag(deliveryTag);
		message.setMessageId(messageId);
		message.setExchange(exchange);
		message.setRoutingKey(routingKey);
		message.setContentType(contentType);
		message.setReplyTo(replyTo);
		message.setCorrelationId(correlationId);
		return message;
	}
	
	public static AMQPMessage createFromMessage(AMQPMessage message, ByteBuffer buffer) {
		requireNonNull(message);
		requireNonNull(buffer);
		AMQPMessageImpl newMessage = new AMQPMessageImpl(isNull(message.getExchange()) ? "" : message.getExchange(), buffer);
		newMessage.setExchange(message.getExchange());
		newMessage.setMessageId(message.getMessageId());
		newMessage.setDeliveryTag(message.getDeliveryTag());
		newMessage.setRoutingKey(message.getRoutingKey());
		newMessage.setContentType(message.getContentType());
		newMessage.setReplyTo(message.getReplyTo());
		newMessage.setCorrelationId(message.getCorrelationId());
		return newMessage;
	}

}
