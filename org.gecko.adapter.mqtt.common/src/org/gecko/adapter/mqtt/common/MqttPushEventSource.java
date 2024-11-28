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

import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.adapter.mqtt.MQTTContext;
import org.gecko.adapter.mqtt.MqttConfig;
import org.gecko.adapter.mqtt.QoS;
import org.gecko.osgi.messaging.Message;
import org.gecko.osgi.messaging.MessagingContext;
import org.gecko.util.pushstream.PushStreamHelper;
import org.osgi.util.promise.Promise;
import org.osgi.util.pushstream.PushEventConsumer;
import org.osgi.util.pushstream.SimplePushEventSource;

public class MqttPushEventSource implements SimplePushEventSource<Message> {
	private static final Logger logger = Logger.getLogger(MqttPushEventSource.class.getName());
	private static final int RECONNECT_DELAY_MS = 5000;
	
	private Timer reconnectTimer;
	private String topic;
	private SimplePushEventSource<Message> source;
	private GeckoMqttClient mqtt;
	private int qos;
	private MqttConfig config;

	private MqttClientFactory<GeckoMqttClient> clientFactory;

	/**
	 * Creates a new instance.
	 */
	MqttPushEventSource(String topic, MessagingContext context, MqttConfig config,
			MqttClientFactory<GeckoMqttClient> clientFactory) {
		this.topic = topic;
		this.config = config;
		this.clientFactory = clientFactory;

		QoS qos = QoS.AT_LEAST_ONE;
		if (context instanceof MQTTContext) {
			MQTTContext ctx = (MQTTContext) context;
			if (ctx.getQoS() != null) {
				qos = ctx.getQoS();
			}
		}
		this.qos = qos.ordinal();

		source = PushStreamHelper.createSimpleEventSource(Message.class, context);
		source.connectPromise().onResolve(this::initMQTTClient);
	}

	@Override
	public AutoCloseable open(PushEventConsumer<? super Message> aec) throws Exception {
		return source.open(aec);
	}

	@Override
	public void close() {
		source.close();
	}

	@Override
	public void publish(Message t) {
		source.publish(t);
	}

	@Override
	public void endOfStream() {
		source.endOfStream();
	}

	@Override
	public void error(Throwable t) {
		source.error(t);
	}

	@Override
	public boolean isConnected() {
		return source.isConnected();
	}

	@Override
	public Promise<Void> connectPromise() {
		return source.connectPromise();
	}

	private void initMQTTClient() {
		mqtt = clientFactory.createClient(config, "gecko" + UUID.randomUUID() + "-" + topic);
		mqtt.subscribe(this.topic, this.qos, this);
		mqtt.connectionLost(this::startReconnectTimer);
	}

	private void startReconnectTimer(Throwable exception) {
		if (exception != null) {
			logger.log(Level.INFO, exception, () -> "Connection to MQTT broker lost: " + exception.getMessage()
					+ ". Waiting before reconnecting.");
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
					logger.log(Level.INFO, "Create new client and subscribe to {0}", topic);
					mqtt.close();
					try {
						initMQTTClient();
					} catch (Exception e) {
						logger.log(Level.SEVERE, e, () -> "Error trying to reconnect to MQTT broker.");
						startReconnectTimer(exception);
					}
				}
			}
		}, RECONNECT_DELAY_MS);
	}

}
