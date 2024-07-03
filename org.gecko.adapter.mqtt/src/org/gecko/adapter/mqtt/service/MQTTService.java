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

import java.net.ConnectException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttClientPersistence;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence;
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
		"vendor=Gecko.io", "implementation=Paho", "mqttVersion=3" })
@Component(service = MessagingService.class, name = "MQTTService", configurationPolicy = ConfigurationPolicy.REQUIRE, immediate = true)
public class MQTTService implements MessagingService, AutoCloseable, MqttCallback {
	private static final Logger logger = Logger.getLogger(MQTTService.class.getName());

	private static final int RECONNECT_DELAY_MS = 5000;

	private Timer reconnectTimer;

	private MqttClient mqtt;

	private volatile Map<String, SimplePushEventSource<Message>> subscriptions = new ConcurrentHashMap<>();

	private Map<String, Integer> reconnectSub = new ConcurrentHashMap<>();

	private MqttConnectOptions options;

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
			options = new MqttConnectOptions();
			if (config.username() != null && config.username().length() != 0) {
				options.setUserName(config.username());
				if (config.password() != null && config.password().length() != 0)
					options.setPassword(config.password().toCharArray());
			}
			options.setMaxInflight(config.maxInflight());
			options.setAutomaticReconnect(true);
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
			mqtt.connect(options);
			mqtt.setCallback(this);
		} catch (Exception e) {
			System.err.println("Error connecting to MQTT broker " + config.brokerUrl());
			throw e;
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
			if (mqtt.isConnected()) {
				mqtt.disconnect();
			}
			mqtt.close();
		}
	}

	@Override
	public void connectionLost(Throwable exception) {
		if (exception != null)
			exception.printStackTrace();
		logger.log(Level.INFO,
				"Connection to MQTT broker lost: " + exception.getMessage() + ". Waiting before reconnecting.");

		if (reconnectTimer != null) {
			reconnectTimer.cancel();
			reconnectTimer = null;
		}

		reconnectTimer = new Timer();
		reconnectTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				try {
					if (mqtt == null) {
						logger.log(Level.SEVERE, "Trying to reconnect a null client.");
						return;
					}
					if (!mqtt.isConnected()) {
						logger.log(Level.INFO, "Reconnect");
						mqtt.connect(options);
					}
				} catch (MqttException e) {
					if (e.getCause() instanceof ConnectException) {
						logger.log(Level.SEVERE, "Error trying to reconnect to MQTT broker.", e);
						connectionLost(exception);
					} else {
						logger.log(Level.SEVERE,
								"Fatal error trying to reconnect to MQTT broker. No further reconnection will be attempted",
								e);
					}
					return;
				}

				for (String topic : reconnectSub.keySet()) {
					try {
						mqtt.subscribe(topic, reconnectSub.get(topic));
					} catch (MqttException e) {
						logger.log(Level.SEVERE,
								"Fatal error trying to subscribe to \"" + topic + "\" MQTT broker while reconnect.", e);
					}
				}
			}
		}, RECONNECT_DELAY_MS);
	}

	@Override
	public void deliveryComplete(IMqttDeliveryToken deliveryComplete) {
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
			synchronized (subscriptions) {
				source = subscriptions.get(filter);
				if (source == null) {
					final SimplePushEventSource<Message> newSource = PushStreamHelper
							.createSimpleEventSource(Message.class, context);
					final int qosInt = qos.ordinal();
					subscriptions.put(filter, newSource);
					reconnectSub.put(topic, qosInt);
					newSource.connectPromise().onResolve(() -> {
						try {
							mqtt.subscribe(topic, qosInt);
						} catch (MqttException e) {
							throw new RuntimeException("Error Connecting subscribing to " + topic, e);
						}
					});
					source = newSource;
				}
			}
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

}