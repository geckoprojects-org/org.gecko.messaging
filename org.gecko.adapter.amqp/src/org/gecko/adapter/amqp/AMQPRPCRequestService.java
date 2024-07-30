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

package org.gecko.adapter.amqp;

import static java.util.Objects.isNull;
import static java.util.Objects.requireNonNull;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import org.gecko.adapter.amqp.api.AMQPConfiguration;
import org.gecko.adapter.amqp.api.BasicAMQPService;
import org.gecko.adapter.amqp.client.AMQPContext;
import org.gecko.adapter.amqp.client.AMQPContextBuilder;
import org.gecko.adapter.amqp.consumer.AMQPHelper;
import org.gecko.adapter.amqp.consumer.AMQPRPCConsumer;
import org.gecko.osgi.messaging.Message;
//import org.gecko.osgi.messaging.MessagingConstants;
import org.gecko.osgi.messaging.MessagingContext;
import org.gecko.osgi.messaging.MessagingRPCService;
import org.osgi.annotation.bundle.Capability;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.util.promise.Promise;
import org.osgi.util.promise.PromiseFactory;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;

/**
 * Implementation of the messaging service for the AMQP protocol, using the RabbitMQ AMQP client
 * @see https://www.rabbitmq.com/api-guide.html
 * @author Mark Hoffmann
 * @since 15.02.2018
 */
@Capability(namespace="gecko.messaging", name="rpcPublisher", version="1.0.0", attribute= {"vendor=Gecko.io", "implementation=AMQP"})
@Component(service=MessagingRPCService.class, name="AMQPRPCRequestService", configurationPolicy=ConfigurationPolicy.REQUIRE, immediate=true)
public class AMQPRPCRequestService extends BasicAMQPService implements MessagingRPCService {

	@Activate	
	public void activate(AMQPConfiguration config, Map<String, Object> properties) throws Exception {
		super.activate(config, properties);
	}

	/**
	 * Called on component deactivation
	 * @throws Exception
	 */
	@Deactivate
	void deactivate() throws Exception {
		close();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingRPCService#publishRPC(java.lang.String, java.nio.ByteBuffer)
	 */
	@Override
	public Promise<Message> publishRPC(String topic, ByteBuffer content) throws Exception {
		requireNonNull(topic);
		requireNonNull(content);
		AMQPContext context = (AMQPContext) new AMQPContextBuilder().topic().queue(topic).build();
		context.setRpc(true);
		return publishRPC(topic, content, context);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingRPCService#publishRPC(java.lang.String, java.nio.ByteBuffer, org.gecko.osgi.messaging.MessagingContext)
	 */
	@Override
	public Promise<Message> publishRPC(String topic, ByteBuffer content, MessagingContext context) throws Exception {
		requireNonNull(topic);
		requireNonNull(content);
		requireNonNull(context);
		if (!(context instanceof AMQPContext)) {
			throw new IllegalArgumentException("MessageContext must be of type AMQPContext");
		}
		AMQPConfiguration configuration = getConfiguration();
		// Mark context as RPC context. The channel will be kept using the message id
		AMQPContext ctx = AMQPContextBuilder.
				createBuilder(configuration).
				asRPCRequest().
				build();
		/*
		 * Add RPC specific settings
		 */
		String uuid = UUID.randomUUID().toString();
		if (isNull(ctx.getCorrelationId())) {
			ctx.setCorrelationId(uuid);
		}
		if (isNull(ctx.getMessageId())) {
			ctx.setMessageId(uuid);
		}
		Channel channel = connectExchange(ctx, false);
		if (channel.isOpen()) {
			if (isNull(ctx.getReplyAddress())) {
				String replyQueueName = channel.queueDeclare().getQueue();
				ctx.setReplyAddress(replyQueueName);
			}
			BasicProperties properties = AMQPHelper.createMessageProperties(ctx);
			byte[] message = content.array();
			AMQPRPCConsumer consumer = new AMQPRPCConsumer(channel, ctx.getCorrelationId());
			// we auto-acknowledging here, because we only expect one answers
			final String ctag = channel.basicConsume(ctx.getReplyAddress(), true, consumer);
			channel.basicPublish(ctx.getExchangeName(), ctx.getRoutingKey(), properties, message);
			return consumer.
					getMessage().
					thenAccept(m->channel.basicCancel(ctag)).
					thenAccept(m->disconnectChannel(ctx, false));
		} else {
			PromiseFactory pf = new PromiseFactory(Executors.newSingleThreadExecutor());
			return pf.failed(new IllegalStateException("Channel is closed"));
		}
	}

}
