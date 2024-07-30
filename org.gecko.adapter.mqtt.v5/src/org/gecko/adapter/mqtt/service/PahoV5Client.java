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

package org.gecko.adapter.mqtt.service;

import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.paho.mqttv5.client.IMqttClient;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttClientPersistence;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptionsBuilder;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.client.persist.MqttDefaultFilePersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
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
public class PahoV5Client implements GeckoMqttClient {

	private static final Logger logger = Logger.getLogger(PahoV5Client.class.getName());

	private IMqttClient client;

	/**
	 * Creates a new instance.
	 */
	public PahoV5Client(MqttConfig config, String id) {

		MqttClientPersistence persistence = new MemoryPersistence();
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

	@SuppressWarnings("deprecation")
	private MqttConnectionOptions getConnectionOptions(MqttConfig config) {
		MqttConnectionOptionsBuilder ob = new MqttConnectionOptionsBuilder();
		if (config.username() != null && config.username().length() != 0) {
			ob.username(config.username());
			if (!DEFAULT_PASSWORD.equals(config._password())) {
				ob.password(config._password().getBytes());
			} else if (config.password().length() != 0) {
				ob.password(config.password().getBytes());
				if (!DEFAULT_PASSWORD.equals(config.password())) {
					logger.log(Level.WARNING,
							"Using deprecated \"password\" attribute in MqttConfig. Please use \".password\" instead.");
				}
			}
		}
		ob.automaticReconnect(true);
		return ob.build();
	}

	@Override
	public void subscribe(String topic, int qos, MqttPushEventSource source) {
		try {
			client.subscribe(topic, qos, (topic1, message) -> {
				if (source.isConnected()) {
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
				logger.log(Level.WARNING,
						"message for client " + client.getClientId() + " not expected topic =  " + topic);
			}

			@Override
			public void mqttErrorOccurred(MqttException exception) {
				logger.log(Level.WARNING, "MQTT error occurred ", exception);
			}

			@Override
			public void deliveryComplete(IMqttToken token) {
				logger.log(Level.FINER, "deliveryComplete " + token);
			}

			@Override
			public void connectComplete(boolean reconnect, String serverURI) {
				logger.log(Level.INFO, "connect to " + serverURI + " complete reconnect = " + reconnect);
			}

			@Override
			public void authPacketArrived(int reasonCode, MqttProperties properties) {
				logger.log(Level.FINER, "auth packet arrived reasonCode = " + reasonCode);
			}

			@Override
			public void disconnected(MqttDisconnectResponse disconnectResponse) {
				consumer.accept(disconnectResponse.getException());
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
