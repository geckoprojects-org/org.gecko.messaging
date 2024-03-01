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

import static java.util.Objects.nonNull;

import org.gecko.adapter.amqp.api.AMQPConfiguration;
import org.gecko.adapter.amqp.client.AMQPContext;

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
		String key = context.getQueueName();
		if (context.isExchangeMode()) {
			String exchange = context.getExchangeName();
			String routingKey = context.getRoutingKey();
			String routingType = context.getRoutingType();
			key += "_" + exchange + "_" + routingKey + "_" + routingType;
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

}
