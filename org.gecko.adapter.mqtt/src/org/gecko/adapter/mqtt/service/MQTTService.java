/**
 * Copyright (c) 2012 - 2017 Data In Motion and others.
 * All rights reserved. 
 * 
 * This program and the accompanying materials are made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
package org.gecko.adapter.mqtt.service;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttClientPersistence;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptionsBuilder;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MqttDefaultFilePersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.gecko.adapter.mqtt.MQTTContext;
import org.gecko.adapter.mqtt.MQTTContextBuilder;
import org.gecko.adapter.mqtt.QoS;
import org.gecko.osgi.messaging.Message;
import org.gecko.osgi.messaging.MessagingConstants;
import org.gecko.osgi.messaging.MessagingContext;
import org.gecko.osgi.messaging.MessagingService;
import org.gecko.osgi.messaging.SimpleMessage;
import org.gecko.util.pushstream.PushStreamHelper;
import org.osgi.annotation.bundle.Capability;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamBuilder;
import org.osgi.util.pushstream.SimplePushEventSource;

/**
 * MQTT messaging service implementation
 * 
 * @author Mark Hoffmann
 * @since 10.10.2017
 */
@Capability(namespace = MessagingConstants.CAPABILITY_NAMESPACE, name = "mqtt.adapter", version = "1.0.0", attribute = {
		"vendor=Gecko.io", "implementation=Paho" })
@Component(service = MessagingService.class, name = "MQTTService", configurationPolicy = ConfigurationPolicy.REQUIRE, immediate = true)
public class MQTTService implements MessagingService, AutoCloseable, MqttCallback {
	private static final Logger logger = Logger.getLogger(MQTTService.class.getName());

	private MqttClient mqtt;

	private volatile Map<String, SimplePushEventSource<Message>> subscriptions = new ConcurrentHashMap<>();

	public MQTTService() {
		// to be used with @Activate
	}

	public MQTTService(MqttClient mqtt) {
		this.mqtt = mqtt;
		this.mqtt.setCallback(this);
	}

	@ObjectClassDefinition
	@interface MqttConfig {

		String brokerUrl();

		String username();

		String password();

		PersistenceType inflightPersistence() default PersistenceType.MEMORY;

		String filePersistencePath() default "";

		int maxThreads() default 0;

		int maxInflight() default 10;

	}

	enum PersistenceType {
		MEMORY, FILE
	}

	@Activate
	void activate(MqttConfig config, BundleContext context) throws Exception {
		String id = UUID.randomUUID().toString();
		try {
			MqttConnectionOptionsBuilder ob = new MqttConnectionOptionsBuilder();
			if (config.username() != null && config.username().length() != 0) {
				ob.username(config.username());
				if (config.password() != null && config.password().length() != 0)
					ob.password(config.password().getBytes());
			}
			ob.automaticReconnect(true);
			MqttClientPersistence persistence = null;
			if (PersistenceType.FILE.equals(config.inflightPersistence())) {
				if (!config.filePersistencePath().isEmpty() && !config.filePersistencePath().equals("")) {
					persistence = new MqttDefaultFilePersistence(config.filePersistencePath());
				} else {
					persistence = new MqttDefaultFilePersistence();
				}
			}
			if (config.maxThreads() > 0) {
				ScheduledExecutorService ses = Executors.newScheduledThreadPool(config.maxThreads());
				mqtt = new MqttClient(config.brokerUrl(), id, persistence, ses);
			} else {
				mqtt = new MqttClient(config.brokerUrl(), id, persistence);
			}
			mqtt.connect(ob.build());
			mqtt.setCallback(this);
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error connecting to MQTT broker " + config.brokerUrl(), e);
		}
	}

	/**
	 * Called on component deactivation
	 * 
	 * @throws Exception
	 */
	@Deactivate
	void deactivate() throws Exception {
		close();
	}

	@Override
	public void close() throws Exception {
		if (mqtt != null) {
			mqtt.disconnect();
			mqtt.close();
		}
	}

	@Override
	public void messageArrived(String topic, MqttMessage message) throws Exception {
		final Iterator<Entry<String, SimplePushEventSource<Message>>> it;
		synchronized (subscriptions) {
			it = subscriptions.entrySet().iterator();
		}
		while (it.hasNext()) {
			Entry<String, SimplePushEventSource<Message>> e = it.next();
			String key = e.getKey();
			boolean match = false;
			if (key.endsWith("#")) {
				key = key.replace("#", "");
				match = topic.startsWith(key) || key.equals(topic);
			} else {
				match = key.equals(topic);
			}
			if (!match) {
				continue;
			}
			SimplePushEventSource<Message> source = e.getValue();
			if (!source.isConnected()) {
				source.close();
				it.remove();
			} else {
				try {
					Message msg = fromPahoMessage(message, topic);
					source.publish(msg);
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}
		}
	}

	@Override
	public PushStream<Message> subscribe(String topic) throws Exception {
		MessagingContext ctx = new MQTTContextBuilder().withQoS(QoS.AT_LEAST_ONE).build();
		return subscribe(topic, ctx);
	}

	@Override
	public PushStream<Message> subscribe(String topic, MessagingContext context) throws Exception {
		QoS qos = QoS.AT_LEAST_ONE;
		if (context != null && context instanceof MQTTContext) {
			MQTTContext ctx = (MQTTContext) context;
			if (ctx.getQoS() != null) {
				qos = ctx.getQoS();
			}
		}
		String filter = topic.replaceAll("\\*", "#"); // replace MQTT # sign with * for filters
		SimplePushEventSource<Message> source = subscriptions.get(filter);
		if (source == null) {
			final SimplePushEventSource<Message> newSource = PushStreamHelper.createSimpleEventSource(Message.class,
					context);
			final int qosInt = qos.ordinal();
			newSource.connectPromise().onResolve(() -> {
				try {
					synchronized (subscriptions) {
						subscriptions.put(filter, newSource);
					}
					mqtt.subscribe(topic, qosInt);
				} catch (MqttException e) {
					throw new RuntimeException("Error Connecting subscribing to " + topic, e);
				}
			});
			source = newSource;
		}
		PushStreamBuilder<Message, BlockingQueue<PushEvent<? extends Message>>> buildStream = PushStreamHelper
				.configurePushStreamBuilder(source, context);
		return buildStream.build();
	}

	@Override
	public void publish(String topic, ByteBuffer content) throws Exception {
		MessagingContext ctx = new MQTTContextBuilder().withQoS(QoS.AT_MOST_ONE).build();
		publish(topic, content, ctx);
	}

	@Override
	public void publish(String topic, ByteBuffer content, MessagingContext context) throws Exception {
		QoS qos = QoS.AT_MOST_ONE;
		boolean retained = false;
		if (context != null && context instanceof MQTTContext) {
			MQTTContext ctx = (MQTTContext) context;
			if (ctx.getQoS() != null) {
				qos = ctx.getQoS();
			}
			retained = ctx.isRetained();
		}
		mqtt.publish(topic, content.array(), qos.ordinal(), retained);
	}

	/**
	 * Converts a Paho {@link MqttMessage} into an own one
	 * 
	 * @param msg   the original message
	 * @param topic the topic
	 * @return the converted message
	 */
	public static Message fromPahoMessage(MqttMessage msg, String topic) {
		ByteBuffer content = ByteBuffer.wrap(msg.getPayload());
		MessagingContext context = new MQTTContextBuilder().setRetained(msg.isRetained())
				.withQoS(QoS.values()[msg.getQos()]).build();
		return new SimpleMessage(topic, content, context);
	}

	@Override
	public void disconnected(MqttDisconnectResponse disconnectResponse) {
		logger.log(Level.FINER, "disconnected " + disconnectResponse);
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
		logger.log(Level.FINER, "connect to " + serverURI + " complete reconnect = " + reconnect);
	}

	@Override
	public void authPacketArrived(int reasonCode, MqttProperties properties) {
		logger.log(Level.FINER, "auth packet arrived reasonCode = " + reasonCode);
	}

}