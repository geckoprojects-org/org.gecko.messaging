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

package org.gecko.adapter.mqtt.common;

import java.nio.ByteBuffer;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.adapter.mqtt.MQTTContext;
import org.gecko.adapter.mqtt.MQTTContextBuilder;
import org.gecko.adapter.mqtt.MqttConfig;
import org.gecko.adapter.mqtt.QoS;
import org.gecko.osgi.messaging.Message;
import org.gecko.osgi.messaging.MessagingContext;
import org.gecko.osgi.messaging.MessagingService;
import org.gecko.util.pushstream.PushStreamHelper;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamBuilder;
import org.osgi.util.pushstream.SimplePushEventSource;

/**
 * Abstract implementation for a MqttServices
 * 
 * @author grune
 * @since Jul 11, 2024
 */
public abstract class AbstractMqttService implements MessagingService, AutoCloseable {

	private static final Logger logger = Logger.getLogger(AbstractMqttService.class.getName());
	private static final int RECONNECT_DELAY_MS = 5000;
	private Timer reconnectTimer;
	protected GeckoMqttClient mqtt;
	private volatile ConcurrentHashMap<String, MqttPushEventSource> subscriptions = new ConcurrentHashMap<>();
	private MqttConfig config;

	@Activate
	public void doActivate(MqttConfig config) throws Exception {
		this.config = config;
	}

	@Deactivate
	public void doDeactivate() throws Exception {
		close();
		if (reconnectTimer != null) {
			reconnectTimer.cancel();
		}
	}

	/**
	 * Specific creation of the client
	 * 
	 * @param config Configuration
	 * @param id     Client Id
	 * @return
	 */
	protected abstract GeckoMqttClient createClient(MqttConfig config, String id);

	@Override
	public void close() throws Exception {
		if (mqtt != null) {
			if (mqtt.isConnected()) {
				mqtt.disconnect();
			}
			mqtt.close();
		}
		subscriptions.values().forEach(MqttPushEventSource::close);
	}

	@Override
	public PushStream<Message> subscribe(String topic) throws Exception {
		MessagingContext ctx = new MQTTContextBuilder().withQoS(QoS.AT_LEAST_ONE).build();
		return subscribe(topic, ctx);
	}

	@Override
	public PushStream<Message> subscribe(String topic, MessagingContext context) throws Exception {
		String filter = topic.replaceAll("\\*", "#"); // replace MQTT # sign with * for filters
		SimplePushEventSource<Message> source = subscriptions.get(filter);
		if (source == null) {
			synchronized (subscriptions) {
				source = subscriptions.get(filter);
				if (source == null) {
					final MqttPushEventSource newSource = new MqttPushEventSource(topic, context, config,
							this::createClient);
					subscriptions.put(filter, newSource);
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
		if (mqtt == null) {
			String id = UUID.randomUUID().toString();
			try {
				mqtt = createClient(config, id);
				mqtt.connectionLost(this::startReconnectTimer);
			} catch (Exception e) {
				logger.log(Level.SEVERE, "Error connecting to MQTT broker " + config.brokerUrl(), e);
				throw e;
			}
		}

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

	private void startReconnectTimer(Throwable exception) {
		if (exception != null) {
			logger.log(Level.INFO,
					"Connection to MQTT broker lost: " + exception.getMessage() + ". Waiting before reconnecting.",
					exception);
		}
		if (reconnectTimer != null) {
			reconnectTimer.cancel();
			reconnectTimer = null;
		}

		reconnectTimer = new Timer();
		reconnectTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				if (mqtt == null) {
					logger.log(Level.SEVERE, "Trying to reconnect a null client.");
					return;
				}
				if (!mqtt.isConnected()) {
					logger.log(Level.INFO, "Reconnect");
					mqtt.connect(config, e -> {
						logger.log(Level.SEVERE, "Error trying to reconnect to MQTT broker.", e);
						startReconnectTimer(exception);
						return false;
					});
				}
			}
		}, RECONNECT_DELAY_MS);
	}

	@Override
	public String toString() {
		return mqtt + " " + subscriptions.toString();
	}

}