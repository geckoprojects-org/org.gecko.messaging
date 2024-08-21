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

package org.gecko.adapter.amqp.api;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNull;
import static org.gecko.adapter.amqp.consumer.AMQPHelper.validateExchangeContext;
import static org.gecko.adapter.amqp.consumer.AMQPHelper.validateExchangeConfiguration;
import static org.gecko.adapter.amqp.consumer.AMQPHelper.validateQueueContext;
import static org.gecko.adapter.amqp.consumer.AMQPHelper.validateQueueConfiguration;

import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.gecko.adapter.amqp.client.AMQPContext;
import org.gecko.adapter.amqp.client.AMQPContext.RoutingType;
import org.gecko.adapter.amqp.consumer.AMQPHelper;
import org.gecko.util.common.PropertyHelper;
import org.gecko.util.common.concurrent.NamedThreadFactory;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.util.converter.Converter;
import org.osgi.util.converter.Converters;
import org.osgi.util.promise.Promise;
import org.osgi.util.promise.PromiseFactory;
import org.osgi.util.promise.Promises;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

/**
 * Basic AMQP service implementation that can be extended to create connections based on an {@link AMQPConfiguration}
 * @author Mark Hoffmann
 * @since 26.02.2024
 */
public abstract class BasicAMQPService {

	private static final Logger logger = Logger.getLogger(BasicAMQPService.class.getName());
	private AMQPConfiguration configuration = null;
	private AMQPProperties amqpProps = null;
	private Map<String, Object> properties;
	private AtomicReference<Connection> connectionRef = new AtomicReference<Connection>();
	private Map<String, Channel> channelMap = new ConcurrentHashMap<String, Channel>();
	private Promise<Connection> connectionPromise;
	private Promise<Channel> channelPromise = null;
	protected final Converter converter = Converters.standardConverter();

	protected void activate(AMQPConfiguration config, Map<String, Object> properties) throws Exception {
		if (!validateConfiguration(config)) {
			throw new ConfigurationException("AMQP-Configuration", "The AMQP configuration is not valid");
		}
		amqpProps = converter.convert(properties).to(AMQPProperties.class);
		this.properties = properties;
		this.configuration = config;
		ExecutorService es = Executors.newSingleThreadExecutor(NamedThreadFactory.newNamedFactory("AMQPService-" + config.name()));
		PromiseFactory pf = new PromiseFactory(es);
		connectionPromise = pf.submit(this::configureConnectionFactory).
				map(this::doConnect).
				thenAccept(this::postConnect).
				onFailure(t -> logger.log(Level.SEVERE, "Error creating AMQP connection", t));
		if (getConfiguration().immediateChannel()) {
			channelPromise = connectionPromise.map(this::configureImmediateChannel);
		}
	}

	/**
	 * Called after the connect
	 */
	protected Connection postConnect(Connection connection) {
		return connection;
	}

	/**
	 * Close the channel in correct mode and the connection afterwards
	 * @throws Exception
	 */
	protected void close() throws Exception {
		if (getConfiguration().immediateChannel()) {
			if (nonNull(channelPromise)) {
				channelPromise.filter(Channel::isOpen).thenAccept(Channel::close);
			}
		} else { 
			channelMap.keySet().forEach((k)->{
				try {
					disconnectChannel(k);
				} catch (Exception e) {
					logger.log(Level.SEVERE, String.format("[%s] Error closing channel", k), e);
				}
			});
			channelMap.clear();
		}
		connectionPromise.
			onResolve(()->{
				Connection c = connectionRef.get();
				if (nonNull(c) && c.isOpen()) {
					c.abort();
					connectionRef.compareAndSet(c, null);
				}
			}).
			onFailure(t -> logger.log(Level.SEVERE, "Error closing AMQP connection ", t));
	}

	/**
	 * Configures the {@link ConnectionFactory}
	 * @return the configured connection factory instance
	 * @throws ConfigurationException
	 */
	protected ConnectionFactory configureConnectionFactory() throws ConfigurationException {
		boolean useUrl = false;
		ConnectionFactory conFactory = new ConnectionFactory();
		AMQPConfiguration cfg = getConfiguration();
		if (cfg.brokerUrl() != null && cfg.brokerUrl().startsWith("amqp://")) {
			try {
				conFactory.setUri(cfg.brokerUrl());
				useUrl = true;
			} catch (KeyManagementException | NoSuchAlgorithmException | URISyntaxException e) {
				logger.log(Level.SEVERE, "Error setting the URI to connection factroy " + cfg.brokerUrl(), e);
			}
		} 
		if (!useUrl) {
			if (!validateConfiguration(cfg)) {
				throw new ConfigurationException("amqp.configuration", "Error validating AMQP configuration, there are missing mandatory values");
			}
			if (cfg.tls()) {
				configureTLSConnection(conFactory, cfg);
			}
			conFactory.setPort(cfg.port());
			conFactory.setHost(cfg.host());
			conFactory.setVirtualHost(cfg.virtualHost());
			if (cfg.username() != null && !cfg.username().isEmpty()) {
				conFactory.setUsername(cfg.username());
			} else {
				Object userValue = PropertyHelper.createHelper().getValue(properties, "username");
				if (userValue != null) {
					conFactory.setUsername(userValue.toString());
				}
			}
			if (cfg.password() != null && !cfg.password().isEmpty()) {
				conFactory.setPassword(cfg.password());
			} else {
				Object passValue = PropertyHelper.createHelper().getValue(properties, "password");
				if (passValue != null) {
					conFactory.setPassword(passValue.toString());
				}
			}
		}
//		if (getConfiguration().autoRecovery()) {
//			conFactory.setAutomaticRecoveryEnabled(getConfiguration().autoRecovery());
//		}
		return conFactory;
	}

	/**
	 * Configures TLS settings
	 * @param connectionFactory the connection factory to configure
	 * @param config the configuration
	 * @throws ConfigurationException thrown when error with TLS configuration occurs
	 */
	protected void configureTLSConnection(ConnectionFactory connectionFactory, AMQPConfiguration config)  throws ConfigurationException {
		requireNonNull(connectionFactory);
		requireNonNull(config);

		String ksPwd = config.keyStorePassword();
		String tsPwd = config.trustStorePassword();
		char[] keyPassphrase = nonNull(ksPwd) ? ksPwd.toCharArray() : "".toCharArray();
		KeyStore ks;
		try {
			ks = KeyStore.getInstance("PKCS12");
			ks.load(new FileInputStream(config.keyStore()), keyPassphrase);

			KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
			kmf.init(ks, keyPassphrase);

			char[] trustPassphrase = nonNull(tsPwd) ? tsPwd.toCharArray() : "".toCharArray();
			KeyStore tks = KeyStore.getInstance("JKS");
			tks.load(new FileInputStream(config.trustStore()), trustPassphrase);

			TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
			tmf.init(tks);

			SSLContext c = SSLContext.getInstance("TLSv1.2");
			c.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

			connectionFactory.useSslProtocol(c);
		} catch (Exception e) {
			throw new ConfigurationException("tls", "TLS configuration is invalid", e);
		}
	}

	protected Connection doConnect(ConnectionFactory connectionFactory) throws IOException, TimeoutException {
		Connection connection = connectionRef.get();
		if (isNull(connection) || 
				!connection.isOpen()) {
			connectionRef.set(connectionFactory.newConnection());
		}
		return connection;
	}
	
	protected Connection ensureOpenConnection() {
		try {
			connectionPromise.getValue();
		} catch (InvocationTargetException e) {
			throw new IllegalStateException("Getting a connection failed.", e.getCause());
		} catch (InterruptedException e) {
			logger.log(Level.SEVERE, "Getting a connection was interrupted.", e);
			Thread.currentThread().interrupt();
		}
		Connection connection = connectionRef.get();
		if (nonNull(connection) && connection.isOpen()) {
			return connection;
		} else {
			throw new IllegalStateException(String.format("Got a non-open connection '%s'", connection));
		}
	}

	/**
	 * Validates the configuration and returns <code>true</code>, if mandatory values are valid
	 * @param config the configuration annotation
	 * @return <code>true</code>, if mandatory values are valid
	 */
	protected boolean validateConfiguration(AMQPConfiguration config) {
		boolean queueOrExchange = validateExchangeConfiguration(config) || validateQueueConfiguration(config);
		boolean basicResult = nonNull(config) && 
				nonNull(config.virtualHost()) &&
				config.port() > 0 && 
				queueOrExchange &&
				nonNull(config.host()) && !config.host().isEmpty();
				if (nonNull(config) && config.tls()) {
					return basicResult &&
							nonNull(config.keyStore()) && !config.keyStore().isBlank() &&
							nonNull(config.trustStore()) && !config.trustStore().isBlank();
				} else {
					return basicResult;
				}
	}

	/** 
	 * Configures a channel out of the connection
	 * @param connection the 
	 * @return the channel
	 * @throws ConfigurationException 
	 */
	protected Channel configureImmediateChannel(Connection connection) throws IOException, TimeoutException, ConfigurationException {
		requireNonNull(connection);
		Channel channel = connection.createChannel(); //NOSONAR
		String exchangeName = getConfiguration().exchange();
		String queueName = getConfiguration().topic();
		String routingKey = getConfiguration().routingKey();
		// exchange mode
		if (validateExchangeConfiguration(getConfiguration())) {
			channel.exchangeDeclare(exchangeName, RoutingType.DIRECT.toString().toLowerCase(), false, false, null);
			// if we have a pre-defined queue, we use it to bind it to the exchange
			if (validateQueueConfiguration(getConfiguration())) {
				channel.queueDeclare(queueName, false, false, false, null);
			} else {
				queueName = channel.queueDeclare().getQueue();
			}
			channel.queueBind(queueName, exchangeName, routingKey);
		} else if (validateQueueConfiguration(getConfiguration())) {
			channel.queueDeclare(queueName, false, false, false, null);
			channel.queuePurge(queueName);
		} else {
			throw new ConfigurationException("topic", "No topic name was provided or no correct exchange setup was provided");
		}

		return channel;
	}

	/**
	 * Disconnects a channel with the given key
	 * @param key
	 * @throws IOException
	 * @throws TimeoutException
	 */
	private void disconnectChannel(String key) throws IOException, TimeoutException {
		Channel channel = channelMap.remove(key);
		if (channel == null) {
			logger.log(Level.INFO, "[{0}] No channel exists. Nothing to disconnect", key);
			return;
		}
		if (channel.isOpen()) {
			channel.close();
		}
	}

	protected void disconnectChannel(AMQPContext context, boolean subscribe) throws IOException, TimeoutException {
		String key = AMQPHelper.getKey(context);
		if (subscribe) {
			key += "_sub";
		}
		disconnectChannel(key);
	}

	/**
	 * Connects using an exchange and routing key to a routing type.
	 * The channels gets an unique id, to keep them till the disconnection.
	 * Usually the queue name is taken as prefix for this key, In single RPC mode,
	 * the message id was taken as prefix
	 * @param context the context
	 * @param subscribe whether to create an exchange for publishing or subscription
	 * @return the channel instance
	 * @throws IOException
	 * @throws TimeoutException
	 */
	protected Channel connectExchange(AMQPContext context, boolean subscribe) throws IOException, TimeoutException {
		if (!validateExchangeContext(context)) {
			throw new IllegalStateException("Error connecting to exchange with invalid context configuration");
		}
		AMQPProperties amqpProps = context.getProperties();
		Channel channel = ensureOpenConnection().createChannel(); //NOSONAR
		String exchangeName = context.getExchangeName();
		String queueName = context.getQueueName();
		String routingKey = context.getRoutingKey();
		String routingType = context.getRoutingType();
		Optional<Channel> channelOpt = getChannel(context, subscribe);
		if (channelOpt.isPresent()) {
			return channelOpt.get();
		}
		Map<String, Object> arguments = new HashMap<String, Object>();
		if (nonNull(amqpProps) && amqpProps.singleActiveConsumer()) {
			arguments.put("x-single-active-consumer", true);
		}
		channel.exchangeDeclare(exchangeName, routingType, context.isDurable(), context.isAutoDelete(), null);
		if (subscribe) {
			if (validateQueueContext(context)) {
				channel.queueDeclare(queueName, false, context.isExclusive(), false, arguments);
			} else {
				queueName = channel.queueDeclare().getQueue();
				context.setQueueName(queueName);
			}
			channel.queueBind(queueName, exchangeName, routingKey);
		}
		registerChannel(channel, context, subscribe);
		return channel;
	}
	
	protected Optional<Channel> getChannel(AMQPContext context, boolean subscribe) {
		String key = AMQPHelper.getKey(context, subscribe);
		if (channelMap.containsKey(key)) {
			logger.log(Level.FINE, "[{0}] Channel already exists", key);
			return Optional.of(channelMap.get(key));
		}
		return Optional.empty();
	}
	
	protected void registerChannel(Channel channel, AMQPContext context, boolean subscribe) {
		String key = AMQPHelper.getKey(context, subscribe);
		if (!channelMap.containsKey(key)) {
			channelMap.put(key, channel);
		}
	}
	
	/**
	 * Connects using a queue name
	 * @param context the context object
	 * @return the channel instance
	 * @throws IOException
	 * @throws TimeoutException
	 */
	protected Channel connectQueue(AMQPContext context) throws IOException, TimeoutException {
		if (validateQueueContext(context)) {
			throw new IllegalStateException("Error connecting to queue with invalid context configuration");
		}
		Channel channel = ensureOpenConnection().createChannel(); //NOSONAR
		String queueName = context.getQueueName();
		if (channelMap.containsKey(queueName)) {
			logger.fine("Channel already created for queue: " + queueName);
			return channelMap.get(queueName);
		}
		channel.queueDeclare(queueName , context.isDurable(), context.isExclusive(), context.isAutoDelete(), null);
		channelMap.put(queueName, channel);
		logger.fine("Created channel for queue: " + queueName);
		return channel;
	}
	
	public AMQPProperties getAmqpProperties() {
		return amqpProps;
	}

	/**
	 * Returns the configuration.
	 * @return the configuration
	 */
	public AMQPConfiguration getConfiguration() {
		return configuration;
	}
	
	public Promise<Channel> getImmediateChannel() {
		return nonNull(channelPromise) ? channelPromise : Promises.failed(new IllegalStateException("Channel not available because no immediate channel was configured!"));
	}
	
	public Promise<Connection> getConnection() {
		return connectionPromise;
	}

}
