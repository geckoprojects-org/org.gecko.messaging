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

package org.gecko.adapter.amqp.client;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;

/**
 * 
 * @author mark
 * @since 20.02.2018
 */
public class AMQPClient {

	private static final Logger logger = Logger.getLogger("o.g.ampqSend"); 
	private ConnectionFactory factory;
	private AtomicReference<Connection> connectionRef = new AtomicReference<Connection>();
	private Map<String, Channel> channelMap = new ConcurrentHashMap<String, Channel>();

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		AMQPClient client = new AMQPClient();
		String QUEUE_NAME = "test_queue";
		String EXCHANGE_NAME = "test.queue";
		String ROUTING_KEY = "myTest";
		Consumer<byte[]> consumer = new Consumer<byte[]>() {

			@Override
			public void accept(byte[] t) {
				logger.info("Consumer received a message: '" + new String(t) + "'");
			}
		};
		CountDownLatch cdl = new CountDownLatch(1);
		try {
			client.registerConsumerQueue(QUEUE_NAME, false, "myTag", consumer);
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Failed registering consumer for queue: " + QUEUE_NAME, e);
		}
		try {
			client.sendSingleWithQueue(QUEUE_NAME, "Hello with Queue: " + QUEUE_NAME);
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Sending failed for queue: " + QUEUE_NAME, e);
		}
		try {
			client.sendSingleWithExchange(EXCHANGE_NAME, ROUTING_KEY, "Hello with Exchange: " + EXCHANGE_NAME + " and routing key: " + ROUTING_KEY);
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Sending failed for exchange: " + EXCHANGE_NAME + " and routing key: " + ROUTING_KEY, e);
		}
		try {
			cdl.await(20, TimeUnit.SECONDS);
		} catch (Exception e) {
			logger.info("Waited 20 seconds, closing client now");
		} finally {
			client.disconnect();
			logger.info("Disconnected client");
		}
	}

	/**
	 * Creates a new instance.
	 */
	public AMQPClient() {
		this(null);
	}

	/**
	 * Creates a new instance.
	 */
	public AMQPClient(String host) {
		factory = new ConnectionFactory();
		if (host == null) {
			//			factory.setHost("dim-rabbitmq");
			factory.setHost("devel.data-in-motion.biz");
		} else {
			factory.setHost(host);
		}
		factory.setPort(5672);
		factory.setUsername("demo");
		factory.setPassword("1234");
		factory.setVirtualHost("test");
		factory.setAutomaticRecoveryEnabled(false);
		factory.setTopologyRecoveryEnabled(false);
	}

	/**
	 * Connects using a queue name
	 * @param queue
	 * @throws IOException
	 * @throws TimeoutException
	 */
	private Channel connectQueue(String queue) throws IOException, TimeoutException {
		if (channelMap.containsKey(queue)) {
			logger.info("Client - Channel already created for queue: " + queue);
			return channelMap.get(queue);
		}
		connect();
		Channel channel = connectionRef.get().createChannel();
		channel.queueDeclare(queue , true, false, false, null);
		channelMap.put(queue, channel);
		logger.info("Client - Created channel for queue: " + queue);
		return channel;
	}

	/**
	 * Connects using an exchange and routing key to a TOPIC routing type fanout
	 * @param exchange
	 * @param routingKey
	 * @return the channel instance
	 * @throws IOException
	 * @throws TimeoutException
	 */
	private Channel connectExchangeFanout(String exchange) throws IOException, TimeoutException {
		return connectExchange(exchange, "", "fanout", false);
	}

	/**
	 * Connects using an exchange and routing key to a TOPIC routing type topic
	 * @param exchange
	 * @param routingKey
	 * @return the channel instance
	 * @throws IOException
	 * @throws TimeoutException
	 */
	private Channel connectExchangeTopic(String exchange, String routingKey) throws IOException, TimeoutException {
		return connectExchange(exchange, routingKey, "topic", true);
	}

	/**
	 * Connects using an exchange and routing key to a DIRECT routing type
	 * @param exchange
	 * @param routingKey
	 * @return the channel instance
	 * @throws IOException
	 * @throws TimeoutException
	 */
	private Channel connectExchangeDirect(String exchange, String routingKey) throws IOException, TimeoutException {
		return connectExchange(exchange, routingKey, "direct", false);
	}

	/**
	 * Disconnects all channels 
	 */
	public void disconnect() {
		channelMap.keySet().forEach((k)->{
			try {
				disconnectChannel(k);
			} catch (AlreadyClosedException e) {
			} catch (Exception e) {
				logger.log(Level.SEVERE, "Error closing channel: " + k, e);
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

	public void purgeChannel(String queue) throws IOException, TimeoutException {
		Channel channel = connectQueue(queue);
		if (channel.isOpen()) {
			channel.queuePurge(queue);
		}
	}

	/**
	 * Sends a message for a queue
	 * @param queue
	 * @param message
	 * @throws IOException
	 * @throws TimeoutException
	 */
	public void sendSingleWithQueue(String queue, String message) throws IOException, TimeoutException {
		Channel channel = connectQueue(queue);
		if (channel.isOpen()) {
			channel.basicPublish("", queue, null, message.getBytes());
		}
	}

	/**
	 * Sends a message for exchange and routing key
	 * @param exchange
	 * @param routingKey
	 * @param message
	 * @throws IOException
	 * @throws TimeoutException
	 */
	public void sendSingleWithExchange(String exchange, String routingKey, String message) throws IOException, TimeoutException {
		Channel channel = connectExchangeTopic(exchange, routingKey);
		if (channel.isOpen()) {
			channel.basicPublish(exchange, routingKey, null, message.getBytes());
		}
	}

	/**
	 * Sends a message for exchange and routing key
	 * @param exchange
	 * @param message
	 * @throws IOException
	 * @throws TimeoutException
	 */
	public void sendSingleWithFanout(String exchange, String message) throws IOException, TimeoutException {
		Channel channel = connectExchangeFanout(exchange);
		if (channel.isOpen()) {
			channel.basicPublish(exchange, "", null, message.getBytes());
		}
	}

	/**
	 * Sends a message for exchange and routing key
	 * @param exchange
	 * @param routingKey
	 * @param message
	 * @throws IOException
	 * @throws TimeoutException
	 */
	public void sendSingleWithExchangeDirect(String exchange, String routingKey, String message) throws IOException, TimeoutException {
		Channel channel = connectExchangeDirect(exchange, routingKey);
		if (channel.isOpen()) {
			channel.basicPublish(exchange, routingKey, null, message.getBytes());
		}
	}

	/**
	 * @param queue
	 * @param autoAck
	 * @param consumerTag
	 * @param consumer
	 * @throws IOException 
	 * @throws TimeoutException 
	 */
	public void registerConsumerFanout(String exchange, Consumer<byte[]> consumer) throws IOException, TimeoutException {
		AtomicReference<String> queueName = new AtomicReference<String>();
		Channel channel = connectExchange(queueName::set, exchange, "", "fanout", true);

	    channel.basicConsume(queueName.get(), true, new DefaultConsumer(channel) {
			@Override
			public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body)	throws IOException {
				logger.log(Level.INFO, "Received fanout message: '" + new String(body));
				consumer.accept(body);
			}
		});
	}
	
	/**
	 * @param queue
	 * @param autoAck
	 * @param consumerTag
	 * @param consumer
	 * @throws IOException 
	 * @throws TimeoutException 
	 */
	public void registerConsumerDirect(String exchange, String bindingKey, Consumer<byte[]> consumer) throws IOException, TimeoutException {
		AtomicReference<String> queueName = new AtomicReference<String>();
		Channel channel = connectExchange(queueName::set, exchange, bindingKey, "direct", true);
		
		channel.basicConsume(queueName.get(), true, new DefaultConsumer(channel) {
			@Override
			public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body)	throws IOException {
				logger.log(Level.INFO, "Received direct message: '" + new String(body));
				consumer.accept(body);
			}
		});
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
		Channel channel = connectQueue(queue);
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

	/**
	 * @param queue
	 * @throws IOException 
	 * @throws TimeoutException 
	 */
	public void registerRPCEcho(String queue) throws IOException, TimeoutException {
		Channel channel = connectQueue(queue);
		if (channel.isOpen()) {
			channel.basicConsume(queue, true, new DefaultConsumer(channel) {
				@Override
				public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body)	throws IOException {
					long deliveryTag = envelope.getDeliveryTag();
					String correlationId = properties.getCorrelationId();
					String replyTo = properties.getReplyTo();
					logger.log(Level.INFO, "Received RPC request: '" + new String(body) + ", deliveryTag: " + deliveryTag);
					AMQP.BasicProperties props = new AMQP.BasicProperties
							.Builder()
							.correlationId(correlationId)
							.build();
					channel.basicPublish("", replyTo, props, body);
					channel.basicAck(deliveryTag, false);
				}
			});
		}
	}

	/**
	 * Connects using an exchange and routing key to a routing type
	 * @param exchange
	 * @param routingKey
	 * @param routingType
	 * @return the channel instance
	 * @throws IOException
	 * @throws TimeoutException
	 */
	private Channel connectExchange(String exchange, String routingKey, String routingType, boolean bind) throws IOException, TimeoutException {
		return connectExchange(null, exchange, routingKey, routingType, bind);
	}
	
	/**
	 * Connects using an exchange and routing key to a routing type
	 * @param exchange
	 * @param bindingKey
	 * @param routingType
	 * @return the channel instance
	 * @throws IOException
	 * @throws TimeoutException
	 */
	private Channel connectExchange(Consumer<String> queueNameConsumer, String exchange, String bindingKey, String routingType, boolean bind) throws IOException, TimeoutException {
		String key = exchange + "_" + bindingKey + "_" + routingType;
		if (channelMap.containsKey(key)) {
			logger.fine("Channel already exists for exchange: " + exchange + " and routing key: " + bindingKey + " and type: " + routingType);
			return channelMap.get(key);
		}
		connect();
		Channel channel = connectionRef.get().createChannel();
		if (routingType.equals("fanout")) {
			channel.exchangeDeclare(exchange, routingType);
		} else {
			channel.exchangeDeclare(exchange, routingType, true);
		}
		if (bind) {
			String queueName = channel.queueDeclare().getQueue();
			channel.queueBind(queueName, exchange, bindingKey);
			if (queueNameConsumer != null) {
				queueNameConsumer.accept(queueName);
			}
		}
		channelMap.put(key, channel);
		logger.fine("Created channel for type '" + routingType + "', exchange: " + exchange + " and routing key: " + bindingKey);
		return channel;
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
			connectionRef.set(factory.newConnection());
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
			logger.warning("No channel exists for key: " + key + " - Nothing to disconnect");
			return;
		}
		channel.close();
	}

}
