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

package org.gecko.adapter.amqp.pubsub;

import static java.util.Objects.nonNull;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.adapter.amqp.api.AMQPConfiguration;
import org.gecko.adapter.amqp.api.WorkerFunction;
import org.gecko.adapter.amqp.client.AMQPContext.RoutingType;
import org.gecko.adapter.amqp.client.AMQPMessage;
import org.gecko.adapter.amqp.pubsub.consumer.AMQPPubOnSubConsumer;
import org.gecko.osgi.messaging.MessagingRPCPubOnSub;
import org.gecko.util.common.PropertyHelper;
import org.gecko.util.common.concurrent.NamedThreadFactory;
import org.gecko.util.pushstream.PushStreamContext;
import org.gecko.util.pushstream.PushStreamHelper;
import org.osgi.annotation.bundle.Capability;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.util.promise.Promise;
import org.osgi.util.promise.PromiseFactory;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

/**
 * Implementation of the messaging service for the AMQP protocol, using the RabbitMQ AMQP client
 * @see https://www.rabbitmq.com/api-guide.html
 * @author Mark Hoffmann
 * @since 15.02.2018
 */
@Capability(namespace="gecko.messaging", name="pubOnSub", version="1.0.0", attribute= {"vendor=Gecko.io", "implementation=AMQP"})
@Component(name="AMQPubOnSubService", configurationPolicy=ConfigurationPolicy.REQUIRE, immediate=true)
public class AMQPubOnSubService implements MessagingRPCPubOnSub {

	private static final Logger logger = Logger.getLogger(AMQPubOnSubService.class.getName());
	@Reference(name="workerFunction")
	private WorkerFunction callbackFunction;
	private AMQPConfiguration configuration = null;
	private Promise<Channel> connectionPromise;
	private AMQPPubOnSubConsumer consumer = null;
	private Map<String, Object> properties;

	@Activate	
	void activate(AMQPConfiguration config, Map<String, Object> properties) throws Exception {
		this.properties = properties;
		if (!validateConfiguration(config)) {
			throw new ConfigurationException("PublishOnSubscribe-Configuration", "The Publish-on-subscibe configuration is not valid");
		}
		this.configuration = config;
		// configure publish on subscribe worker
		ExecutorService es = Executors.newSingleThreadExecutor(NamedThreadFactory.newNamedFactory("PublishOnSubscribe-" + config.name()));
		PromiseFactory pf = new PromiseFactory(es);
		connectionPromise = pf.submit(this::configureConnectionFactory).
				map(this::configureConnection).
				thenAccept(this::configureChannel).
				onFailure(t -> logger.log(Level.SEVERE, "Error creating AMQP publish on subscribe connection", t));
	}
	
	/**
	 * Called on component deactivation
	 * @throws Exception
	 */
	@Deactivate
	void deactivate() throws Exception {
		if (consumer != null) {
			consumer.close();
		}
		connectionPromise.
			thenAccept(this::closeChannel).
			onFailure(t -> logger.log(Level.SEVERE, "Error closing connection ", t));
	}
	
	/**
	 * Configures the {@link ConnectionFactory}
	 * @param context the publish on subscribe context
	 * @return the configures context with the connection factory instance
	 * @throws ConfigurationException
	 */
	private ConnectionFactory configureConnectionFactory() throws ConfigurationException {
		if (configuration == null) {
			throw new IllegalArgumentException("Cannot create a connection factory without a configuration");
		}
		boolean useUrl = false;
		ConnectionFactory conFactory = new ConnectionFactory();
		if (configuration.brokerUrl() != null && configuration.brokerUrl().startsWith("amqp://")) {
			try {
				conFactory.setUri(configuration.brokerUrl());
				useUrl = true;
			} catch (KeyManagementException | NoSuchAlgorithmException | URISyntaxException e) {
				logger.log(Level.SEVERE, "Error setting the URI to connection factroy " + configuration.brokerUrl(), e);
			}
		} 
		if (!useUrl && validateConfiguration(configuration)) {
			conFactory.setPort(configuration.port());
			conFactory.setHost(configuration.host());
			conFactory.setVirtualHost(configuration.virtualHost());
			if (configuration.username() != null && !configuration.username().isEmpty()) {
				conFactory.setUsername(configuration.username());
			} else {
				Object userValue = PropertyHelper.createHelper().getValue(properties, "username");
				if (userValue != null) {
					conFactory.setUsername(userValue.toString());
				}
			}
			if (configuration.password() != null && !configuration.password().isEmpty()) {
				conFactory.setPassword(configuration.password());
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
		if (configuration.autoRecovery()) {
			conFactory.setAutomaticRecoveryEnabled(configuration.autoRecovery());
		}
		return conFactory;
	}
	
	private Channel configureConnection(ConnectionFactory connectionfactory) throws IOException, TimeoutException {
		Connection connection = connectionfactory.newConnection();
		Channel channel = connection.createChannel();
		return channel;
	}
	
	private Channel configureChannel(Channel channel) throws IOException, TimeoutException {
		String exchangeName = configuration.exchange();
		String routingKey = configuration.routingKey();
		// exchange mode
		if (nonNull(routingKey) && !routingKey.isBlank() && 
				nonNull(exchangeName) && !exchangeName.isBlank()) {
			String exchange = exchangeName;
			channel.exchangeDeclare(exchange, RoutingType.DIRECT.toString().toLowerCase(), false, false, null);
			String queueName = channel.queueDeclare().getQueue();
			channel.queueBind(queueName, exchange, routingKey);
		} else {
			channel.queueDeclare(exchangeName, false, false, false, null);
			channel.queuePurge(exchangeName);
		}
		channel.basicQos(1);
		PushStreamContext<AMQPMessage> ctx = PushStreamHelper.getPushStreamContext(properties);
		consumer = new AMQPPubOnSubConsumer(channel, callbackFunction, ctx);
		channel.basicConsume(exchangeName, false, consumer);
		return channel;
	}
	
	private void closeChannel(Channel channel) throws IOException, TimeoutException {
		if (channel != null && channel.isOpen()) {
			channel.close();
		}
	}

	/**
	 * Validates the configuration and returns <code>true</code>, if mandatory values are valid
	 * @param config the configuration annotation
	 * @return <code>true</code>, if mandatory values are valid
	 */
	private boolean validateConfiguration(AMQPConfiguration config) {
		return nonNull(config) && 
				nonNull(config.virtualHost()) &&
				config.port() > 0 && 
				nonNull(config.host()) && !config.host().isEmpty() && 
				nonNull(config.topic()) && !config.topic().isBlank();
	}
	
}
