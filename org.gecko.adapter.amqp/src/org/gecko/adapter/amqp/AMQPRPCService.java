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
package org.gecko.adapter.amqp;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.adapter.amqp.client.AMQPContext;
import org.gecko.adapter.amqp.client.AMQPContextBuilder;
import org.gecko.adapter.amqp.consumer.AMQPHelper;
import org.gecko.adapter.amqp.consumer.AMQPRPCConsumer;
import org.gecko.osgi.messaging.Message;
//import org.gecko.osgi.messaging.MessagingConstants;
import org.gecko.osgi.messaging.MessagingContext;
import org.gecko.osgi.messaging.MessagingRPCService;
import org.gecko.util.common.PropertyHelper;
import org.osgi.annotation.bundle.Capability;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.osgi.util.promise.Promise;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;

/**
 * Implementation of the messaging service for the AMQP protocol, using the RabbitMQ AMQP client
 * @see https://www.rabbitmq.com/api-guide.html
 * @author Mark Hoffmann
 * @since 15.02.2018
 */
@Capability(namespace="gecko.messaging", name="rpc", version="1.0.0", attribute= {"vendor=Gecko.io", "implementation=AMQP"})
@Component(service=MessagingRPCService.class, name="AMQPRPCService", configurationPolicy=ConfigurationPolicy.REQUIRE, immediate=true)
public class AMQPRPCService implements MessagingRPCService, AutoCloseable {

	private static final Logger logger = Logger.getLogger(AMQPRPCService.class.getName());
	private AtomicReference<Connection> connectionRef = new AtomicReference<Connection>();
	private ConnectionFactory connectionFactory;

	@ObjectClassDefinition
	@interface AMQPConfig {

		String username();
		String password();
		String host() default "localhost";
		int port() default 5672;
		String virtualHost() default "";
		boolean autoRecovery() default false;
		boolean jmx() default false;
		String brokerUrl();

	}		

	@Activate	
	void activate(AMQPConfig config, Map<String, Object> properties) throws Exception {
		try {
			connectionFactory = configureConnectionFactory(config, properties);
			connect();
		} catch(Exception e){
			logger.log(Level.SEVERE, "Error creating AMQP connection", e);
			throw e;
		}
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
	 * @see java.lang.AutoCloseable#close()
	 */
	@Override
	public void close() throws Exception {
		Connection connection = connectionRef.get();
		if (connection != null) {
			try {
				connection.close();
			} catch (Exception e) {
				logger.log(Level.SEVERE, "Error closing connection ", e);
			}
		}
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingRPCService#publishRPC(java.lang.String, java.nio.ByteBuffer)
	 */
	@Override
	public Promise<Message> publishRPC(String topic, ByteBuffer content) throws Exception {
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
		if (context != null && context instanceof AMQPContext) {
			AMQPContext ctx = (AMQPContext) context;
			ctx.setQueueName(topic);
			final Channel channel = ctx.isExchangeMode() ? connectExchange(ctx, false) : connectQueue(ctx);
			/*
			 * Add RPC specific settings
			 */
			if (ctx.getReplyAddress() == null) {
				String replyQueueName = channel.queueDeclare().getQueue();
				ctx.setReplyAddress(replyQueueName);
			}
			if (ctx.getCorrelationId() == null) {
				String uuid = UUID.randomUUID().toString();
				ctx.setCorrelationId(uuid);
			}
			BasicProperties properties = AMQPHelper.createMessageProperties(ctx);
			if (channel.isOpen()) {
				byte[] message = content.array();
				AMQPRPCConsumer consumer = new AMQPRPCConsumer(channel, ctx.getCorrelationId());
				// we auto-acknowledging here, because we only expect one answers
				final String ctag = channel.basicConsume(ctx.getReplyAddress(), true, consumer);
				if (ctx.isExchangeMode()) {
					channel.basicPublish(ctx.getExchangeName(), ctx.getRoutingKey(), properties, message);
				} else {
					channel.basicPublish("", ctx.getQueueName(), properties, message);
				}
				return consumer.
						getMessage().
						thenAccept(m->channel.basicCancel(ctag)).
						thenAccept(m->channel.close());
			} else {
				throw new IllegalStateException("Channel is closed");
			}
		} 

		throw new IllegalStateException("Invalid context was provided. Please use an AMQPContextBuilder to create the right one");
	}

	/**
	 * Configures the {@link ConnectionFactory}
	 * @param config the configuration admin configuration
	 * @return the configures connection factory instance
	 * @throws ConfigurationException
	 */
	private ConnectionFactory configureConnectionFactory(AMQPConfig config, Map<String, Object> properties) throws ConfigurationException {
		if (config == null) {
			throw new IllegalArgumentException("Cannot create a connection factory without a configuration");
		}
		boolean useUrl = false;
		ConnectionFactory conFactory = new ConnectionFactory();
		if (config.brokerUrl() != null && config.brokerUrl().startsWith("amqp://")) {
			try {
				conFactory.setUri(config.brokerUrl());
				useUrl = true;
			} catch (KeyManagementException | NoSuchAlgorithmException | URISyntaxException e) {
				logger.log(Level.SEVERE, "Error setting the URI to connection factroy " + config.brokerUrl(), e);
			}
		} 
		if (!useUrl && validateConfiguration(config)) {
			conFactory.setPort(config.port());
			conFactory.setHost(config.host());
			conFactory.setVirtualHost(config.virtualHost());
			if (config.username() != null && !config.username().isEmpty()) {
				conFactory.setUsername(config.username());
			} else {
				Object userValue = PropertyHelper.createHelper().getValue(properties, "username");
				if (userValue != null) {
					conFactory.setUsername(userValue.toString());
				}
			}
			if (config.password() != null && !config.password().isEmpty()) {
				conFactory.setPassword(config.password());
			} else {
				Object passValue = PropertyHelper.createHelper().getValue(properties, "password");
				if (passValue != null) {
					conFactory.setPassword(passValue.toString());
				}
			}
		} else {
			if (!useUrl) {
				throw new ConfigurationException("amqp.configuration", "Error validating AMQP configuration, there are missing mandatory values");
			}
		}
		if (config.autoRecovery()) {
			conFactory.setAutomaticRecoveryEnabled(config.autoRecovery());
		}
		return conFactory;
	}

	/**
	 * Validates the configuration and returns <code>true</code>, if mandatory values are valid
	 * @param config the configuration annotation
	 * @return <code>true</code>, if mandatory values are valid
	 */
	private boolean validateConfiguration(AMQPConfig config) {
		return config != null & 
				config.port() > 0 && 
				config.host() != null && !config.host().isEmpty() && 
				config.virtualHost() != null && !config.virtualHost().isEmpty();
	}

	/**
	 * Creates a new connection
	 * @throws IOException
	 * @throws TimeoutException
	 */
	private void connect() throws IOException, TimeoutException {
		Connection connection = connectionRef.get();
		if (connection == null || 
				!connection.isOpen()) {
			connectionRef.set(connectionFactory.newConnection());
		}
	}

	/**
	 * Connects using an exchange and routing key to a routing type
	 * @param context the context object
	 * @return the channel instance
	 * @throws IOException
	 * @throws TimeoutException
	 */
	private Channel connectExchange(AMQPContext context, boolean subscribe) throws IOException, TimeoutException {
		String exchange = context.getExchangeName();
		String routingKey = context.getRoutingKey();
		String routingType = context.getRoutingType();
		String key = AMQPHelper.getKey(context);
		if (subscribe) {
			key += "_sub";
		}
		connect();
		Channel channel = connectionRef.get().createChannel();
		channel.exchangeDeclare(exchange, routingType, context.isDurable(), context.isAutoDelete(), null);
		if (subscribe) {
			String queueName = channel.queueDeclare().getQueue();
			context.setQueueName(queueName);
			channel.queueBind(queueName, exchange, routingKey);
		}
		logger.log(Level.FINE, "[{0}] Created channel", key);
		return channel;
	}

	/**
	 * Connects using a queue name
	 * @param context the context object
	 * @return the channel instance
	 * @throws IOException
	 * @throws TimeoutException
	 */
	private Channel connectQueue(AMQPContext context) throws IOException, TimeoutException {
//		String queue = context.getQueueName();
		connect();
		Channel channel = connectionRef.get().createChannel();
//		channel.queueDeclare(queue , context.isDurable(), context.isExclusive(), context.isAutoDelete(), null);
		return channel;
	}

	/**
	 * @param queue
	 * @param autoAck
	 * @param consumerTag
	 * @param consumer
	 * @throws IOException 
	 * @throws TimeoutException 
	 */
	public void registerConsumerQueue(String queue, boolean autoAck, String consumerTag, Consumer<byte[]> consumer) throws IOException, TimeoutException {
		AMQPContextBuilder ctxBuilder = new AMQPContextBuilder().topic().queue(queue);
		if (autoAck) {
			ctxBuilder.autoAcknowledge();
		}
		AMQPContext context = (AMQPContext) ctxBuilder.build();
		Channel channel = connectQueue(context);
		if (channel.isOpen()) {
			channel.basicConsume(queue, autoAck, consumerTag, new DefaultConsumer(channel) {
				@Override
				public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body)	throws IOException {
					String routingKey = envelope.getRoutingKey();
					String contentType = properties.getContentType();
					long deliveryTag = envelope.getDeliveryTag();
					logger.log(Level.INFO, "Received message: '" + new String(body) + "' with routingKey: " + routingKey + ", contentType: " + contentType + ", deliveryTag: " + deliveryTag);
					consumer.accept(body);
					channel.basicAck(deliveryTag, false);
				}
			});
		}
	}
}
