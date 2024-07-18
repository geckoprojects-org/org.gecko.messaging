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

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MBeanServer;

import org.gecko.adapter.amqp.client.AMQPContext;
import org.gecko.adapter.amqp.client.AMQPContextBuilder;
import org.gecko.adapter.amqp.consumer.AMQPAcknowledgingConsumer;
import org.gecko.adapter.amqp.consumer.AMQPHelper;
import org.gecko.adapter.amqp.jmx.AMQPConsumerMetric;
import org.gecko.adapter.amqp.jmx.AMQPServiceMetric;
import org.gecko.osgi.messaging.Message;
//import org.gecko.osgi.messaging.MessagingConstants;
import org.gecko.osgi.messaging.MessagingContext;
import org.gecko.osgi.messaging.MessagingService;
import org.gecko.util.common.PropertyHelper;
import org.gecko.util.pushstream.PushStreamHelper;
import org.gecko.util.pushstream.SimplePushEventSourceContext;
import org.osgi.framework.Constants;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.osgi.util.pushstream.PushStream;

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
//@Capability(namespace=MessagingConstants.CAPABILITY_NAMESPACE, name="amqp.adapter", version="1.0.0", attribute= {"vendor=Gecko.io", "implementation=RabbitMQ"})
@Component(service=MessagingService.class, name="AMQPService", configurationPolicy=ConfigurationPolicy.REQUIRE, immediate=true)
public class AMQPService implements MessagingService, AutoCloseable {

	private static final Logger logger = Logger.getLogger("o.g.a.amqpService");
	private Map<String, AMQPAcknowledgingConsumer> consumerMap = new ConcurrentHashMap<>();
	private AtomicReference<Connection> connectionRef = new AtomicReference<Connection>();
	private Map<String, Channel> channelMap = new ConcurrentHashMap<String, Channel>();
	private ConnectionFactory connectionFactory;
	private SimplePushEventSourceContext<Message> esContext;
	private AMQPServiceMetric jmxService;
	private MBeanServer mbeanServer;

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
			esContext = PushStreamHelper.getEventSourceContext(properties);
			updateServiceJMX(config, properties);
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
		channelMap.keySet().forEach((k)->{
			try {
				disconnectChannel(k);
			} catch (Exception e) {
				logger.log(Level.SEVERE, String.format("[%s] Error closing channel", k), e);
			}
		});
		channelMap.clear();
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
	 * @see org.gecko.osgi.messaging.MessagingService#publish(java.lang.String, java.nio.ByteBuffer)
	 */
	@Override
	public void publish(String topic, ByteBuffer content) throws Exception {
		MessagingContext context = new AMQPContextBuilder().topic().durable().queue(topic).build();
		publish(topic, content, context);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingService#publish(java.lang.String, java.nio.ByteBuffer, org.gecko.osgi.messaging.MessagingContext)
	 */
	@Override
	public void publish(String topic, ByteBuffer content, MessagingContext context) throws Exception {
		Channel channel = null;
		if (context != null && context instanceof AMQPContext) {
			AMQPContext ctx = (AMQPContext) context;
			ctx.setQueueName(topic);
			channel = ctx.isExchangeMode() ? connectExchange(ctx, false) : connectQueue(ctx);
			/*
			 * Add RPC specific settings
			 */
			if (ctx.isRpc()) {
				if (ctx.getReplyAddress() == null) {
					String replyQueueName = channel.queueDeclare().getQueue();
					ctx.setReplyAddress(replyQueueName);
				}
				if (ctx.getCorrelationId() == null) {
					String uuid = UUID.randomUUID().toString();
					ctx.setCorrelationId(uuid);
				}
			}
			BasicProperties properties = AMQPHelper.createMessageProperties(ctx);
			if (channel.isOpen()) {
				byte[] message = content.array();
				if (ctx.isExchangeMode()) {
					channel.basicPublish(ctx.getExchangeName(), ctx.getRoutingKey(), properties, message);
				} else {
					channel.basicPublish("", ctx.getQueueName(), properties, message);
				}
			}
		}
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingService#subscribe(java.lang.String)
	 */
	@Override
	public PushStream<Message> subscribe(String topic) throws Exception {
		MessagingContext context = new AMQPContextBuilder().topic().queue(topic).durable().build();
		return subscribe(topic, context);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingService#subscribe(java.lang.String, org.gecko.osgi.messaging.MessagingContext)
	 */
	@Override
	public PushStream<Message> subscribe(String topic, MessagingContext context) throws Exception {
		if (context != null && context instanceof AMQPContext) {
			AMQPContext ctx = (AMQPContext) context;
			String consumerKey = AMQPHelper.getKey(ctx);
			AMQPAcknowledgingConsumer consumer = consumerMap.get(consumerKey); 
			String consumerTag = "ma_" + topic;
			Channel channel = ctx.isExchangeMode() ? connectExchange(ctx, true) : connectQueue(ctx);
			if (channel.isOpen()) {
				if (consumer == null) {
					consumer = new AMQPAcknowledgingConsumer(channel, topic, ctx.getAcknowledgeFilter(), esContext);
					if (mbeanServer != null) {
						AMQPConsumerMetric jmxConsumer = createJMXConsumer(ctx, consumerKey);
						consumer.setMBean(jmxConsumer);
						mbeanServer.registerMBean(jmxConsumer, jmxConsumer.getObjectName());
					}
					consumerMap.put(consumerKey, consumer);
					channel.basicConsume(ctx.getQueueName(), ctx.isAutoAcknowledge(), consumerTag, consumer);
				} 
				return consumer.createPushstream(context);
			} else {
				AMQPAcknowledgingConsumer c = consumerMap.remove(consumerKey);
				c.close();
				channel.close();
				throw new IllegalStateException("The channel to connect is not open");
			}
		} else {
			throw new IllegalArgumentException("The message context is not of type AMQPContext");
		}
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
	 * Disconnects a channel with the given key
	 * @param key
	 * @throws IOException
	 * @throws TimeoutException
	 */
	private void disconnectChannel(String key) throws IOException, TimeoutException {
		Channel channel = channelMap.get(key);
		if (channel == null) {
			logger.log(Level.INFO, "[{0}] No channel exists. Nothing to disconnect", key);
			return;
		}
		if (channel.isOpen()) {
			channel.close();
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
		if (channelMap.containsKey(key)) {
			logger.log(Level.FINE, "[{0}] Channel already exists", key);
			return channelMap.get(key);
		}
		connect();
		Channel channel = connectionRef.get().createChannel();
		if (jmxService != null) {
			jmxService.addChannel(key);
		}
		channel.exchangeDeclare(exchange, routingType, context.isDurable(), context.isAutoDelete(), null);
		if (subscribe) {
			String queueName = channel.queueDeclare().getQueue();
			context.setQueueName(queueName);
			channel.queueBind(queueName, exchange, routingKey);
		}
		channelMap.put(key, channel);
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
		String queue = context.getQueueName();
		if (channelMap.containsKey(queue)) {
			logger.fine("Channel already created for queue: " + queue);
			return channelMap.get(queue);
		}
		connect();
		Channel channel = connectionRef.get().createChannel();
		if (jmxService != null) {
			jmxService.addChannel(queue);
		}
		channel.queueDeclare(queue , context.isDurable(), context.isExclusive(), context.isAutoDelete(), null);
		channelMap.put(queue, channel);
		logger.fine("Created channel for queue: " + queue);
		return channel;
	}

	/**
	 * @param config 
	 * @param properties 
	 * 
	 */
	private void updateServiceJMX(AMQPConfig config, Map<String, Object> properties) {
		if (config.jmx()) {
			String pid = (String) properties.getOrDefault(Constants.SERVICE_PID, "AMQP");
			jmxService = new AMQPServiceMetric();
			jmxService.setName(pid);
			jmxService.setHost(config.host());
			jmxService.setVHost(config.virtualHost());
			jmxService.setPort(config.port());
			int size = esContext.getBufferSize() <= 0 ? 32 : esContext.getBufferSize();
			jmxService.setEventSourceBufferSize(size);
			String policyName = (String) properties.getOrDefault("pushstream.ses.queue.policy.name", "FAIL");
			jmxService.setEventSourceQueuePolicy(policyName);
			if (mbeanServer == null) {
				mbeanServer = ManagementFactory.getPlatformMBeanServer();
			}
			try {
				mbeanServer.registerMBean(jmxService, jmxService.getObjectName());
			} catch (Exception e) {
				logger.log(Level.SEVERE, "Error registering AMQPServiceBean " + jmxService.getName(), e);
			}
		}
	}

	/**
	 * @param ctx
	 * @return
	 */
	private AMQPConsumerMetric createJMXConsumer(AMQPContext ctx, String consumerKey) {
		AMQPConsumerMetric consumerBean = new AMQPConsumerMetric();
		consumerBean.setName(consumerKey);
		consumerBean.setExchange(ctx.getExchangeName());
		consumerBean.setRoutingKey(ctx.getRoutingKey());
		consumerBean.setQueueName(ctx.getQueueName());
		return consumerBean;
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
