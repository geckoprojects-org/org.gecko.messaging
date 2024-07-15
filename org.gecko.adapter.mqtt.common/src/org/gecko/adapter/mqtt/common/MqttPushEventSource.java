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
package org.gecko.adapter.mqtt.common;

import java.util.UUID;

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
	private String topic;
	private SimplePushEventSource<Message> source;
	private GeckoMqttClient mqttClient;
	private int qos;
	private String id;
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
		this.id = UUID.randomUUID() + "-" + topic;

		QoS qos = QoS.AT_LEAST_ONE;
		if (context != null && context instanceof MQTTContext) {
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
		if (mqttClient != null) {
			if (mqttClient.isConnected()) {
				mqttClient.disconnect();
			}
			mqttClient.close();
		}
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
		mqttClient = clientFactory.createClient(config, id);
		mqttClient.subscribe(this.topic, this.qos, this);
	}

}
