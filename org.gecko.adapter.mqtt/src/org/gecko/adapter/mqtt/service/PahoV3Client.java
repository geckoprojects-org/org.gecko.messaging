/**
 * Copyright (c) 2012 - 2024 Data In Motion and others.
 * All rights reserved. 
 * 
 * This program and the accompanying materials are made available under the terms of the 
 * Eclipse Public License v2.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 * 
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
package org.gecko.adapter.mqtt.service;

import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttClientPersistence;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence;
import org.gecko.adapter.mqtt.MQTTContextBuilder;
import org.gecko.adapter.mqtt.MqttConfig;
import org.gecko.adapter.mqtt.PersistenceType;
import org.gecko.adapter.mqtt.QoS;
import org.gecko.adapter.mqtt.common.GeckoMqttClient;
import org.gecko.adapter.mqtt.common.MqttPushEventSource;
import org.gecko.osgi.messaging.Message;
import org.gecko.osgi.messaging.MessagingContext;
import org.gecko.osgi.messaging.SimpleMessage;

/**
 * Facade for Paho MQTT client Version 3
 * 
 * @author grune
 * @since Jul 11, 2024
 */
public class PahoV3Client implements GeckoMqttClient {
	private static final Logger logger = Logger.getLogger(PahoV3Client.class.getName());

	private IMqttClient client;

	/**
	 * Creates a new instance.
	 */
	public PahoV3Client(MqttConfig config, String id) {

		MqttClientPersistence persistence = null;
		if (PersistenceType.FILE.equals(config.inflightPersistence())) {
			if (!config.filePersistencePath().isEmpty() && !config.filePersistencePath().equals("")) {
				persistence = new MqttDefaultFilePersistence(config.filePersistencePath());
			} else {
				persistence = new MqttDefaultFilePersistence();
			}
		}
		try {
			if (config.maxThreads() > 0) {
				ScheduledExecutorService ses = Executors.newScheduledThreadPool(config.maxThreads());
				client = new MqttClient(config.brokerUrl(), id, persistence, ses);
			} else {
				client = new MqttClient(config.brokerUrl(), id, persistence);
			}
			client.connect(getConnectionOptions(config));
		} catch (MqttException e) {
			logger.log(Level.SEVERE, "Fatal error trying to initalize MQTT client in connetion " + id + ".", e);
			throw new RuntimeException(e);
		}
	}

	@Override
	public boolean isConnected() {
		return client.isConnected();
	}

	@Override
	public void disconnect() {
		try {
			client.disconnect();
		} catch (MqttException e) {
			logger.log(Level.SEVERE, "Fatal error while disconnectiong connetion " + client.getClientId() + ".", e);
		}
	}

	@Override
	public void close() {
		try {
			client.close();
		} catch (MqttException e) {
			logger.log(Level.SEVERE, "Fatal error while close connetion " + client.getClientId() + ".", e);
		}
	}

	@Override
	public boolean connect(MqttConfig config, Function<Exception, Boolean> onException) {
		try {
			client.connect(getConnectionOptions(config));
			return true;
		} catch (MqttException e) {
			return onException.apply(e);
		}
	}

	private MqttConnectOptions getConnectionOptions(MqttConfig config) {
		MqttConnectOptions options = new MqttConnectOptions();
		if (config.username() != null && config.username().length() != 0) {
			options.setUserName(config.username());
			if (config._password() != null && config._password().length() != 0)
				options.setPassword(config._password().toCharArray());
		}
		options.setMaxInflight(config.maxInflight());
		options.setAutomaticReconnect(true);
		return options;
	}

	@Override
	public void subscribe(String topic, int qos, MqttPushEventSource source) {
		try {
			client.subscribe(topic, qos, (topic1, message) -> {
				if (!source.isConnected()) {
					source.close();
				} else {
					try {
						Message msg = fromPahoMessage(message, topic1);
						source.publish(msg);
					} catch (Exception e) {
						source.error(e);
						logger.log(Level.SEVERE, "Fatal error while publish to push event source in connetion "
								+ client.getClientId() + ".", e);
					}
				}
			});
		} catch (MqttException e) {
			logger.log(Level.SEVERE,
					"Fatal error trying to subscribe to \"" + topic + "\" MQTT broker while reconnect.", e);
		}

	}

	@Override
	public void connectionLost(Consumer<Throwable> consumer) {
		client.setCallback(new MqttCallback() {

			@Override
			public void messageArrived(String topic, MqttMessage message) throws Exception {
			}

			@Override
			public void deliveryComplete(IMqttDeliveryToken token) {
				logger.log(Level.FINER, "deliveryComplete " + token);
			}

			@Override
			public void connectionLost(Throwable cause) {
				consumer.accept(cause);
			}
		});
	}

	@Override
	public void publish(String topic, byte[] content, int qos, boolean retained) throws Exception {
		client.publish(topic, content, qos, retained);
	}

	@Override
	public String toString() {
		return client.getClientId();
	}
	
	private static Message fromPahoMessage(MqttMessage msg, String topic) {
		ByteBuffer content = ByteBuffer.wrap(msg.getPayload());
		MessagingContext context = new MQTTContextBuilder().setRetained(msg.isRetained())
				.withQoS(QoS.values()[msg.getQos()]).build();
		return new SimpleMessage(topic, content, context);
	}
}
