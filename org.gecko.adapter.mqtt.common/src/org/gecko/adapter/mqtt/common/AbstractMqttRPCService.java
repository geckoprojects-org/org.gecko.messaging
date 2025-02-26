/**
 * Copyright (c) 2012 - 2025 Data In Motion and others.
 * All rights reserved. 
 * 
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 * 
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
package org.gecko.adapter.mqtt.common;

import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.adapter.mqtt.MQTTContext;
import org.gecko.adapter.mqtt.MQTTContextBuilder;
import org.gecko.adapter.mqtt.MqttConfig;
import org.gecko.adapter.mqtt.QoS;
import org.gecko.osgi.messaging.Message;
import org.gecko.osgi.messaging.MessagingContext;
import org.gecko.osgi.messaging.MessagingRPCService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.util.promise.Promise;

/**
 * Abstract implementation for a MqttServices
 * 
 * @author grune
 * @since Feb 26, 2025
 */
public abstract class AbstractMqttRPCService implements MessagingRPCService, AutoCloseable {

	private static final Logger logger = Logger.getLogger(AbstractMqttRPCService.class.getName());
	protected GeckoMqttClient mqtt;
	private MqttConfig config;

	@Activate
	public void doActivate(MqttConfig config) {
		this.config = config;
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

	/**
	 * Specific creation of the client
	 * 
	 * @param config Configuration
	 * @param id     Client Id
	 * @return
	 */
	protected abstract GeckoMqttClient createClient(MqttConfig config, String id);

	
	@Override
	public Promise<Message> publishRPC(String topic, ByteBuffer content) throws Exception {
		MessagingContext ctx = new MQTTContextBuilder().withQoS(QoS.AT_LEAST_ONE).replyTo(topic).build();
		return publishRPC(topic, content, ctx);
	}
	
	@Override
	public Promise<Message> publishRPC(String topic, ByteBuffer content, MessagingContext context) throws Exception {
		if (mqtt == null) {
			try {
				mqtt = createClient(config, generateClientId());
			} catch (Exception e) {
				logger.log(Level.SEVERE, e, () -> "Error connecting to MQTT broker " + config.brokerUrl());
				throw e;
			}
		}

		QoS qos = QoS.AT_MOST_ONE;
		boolean retained = false;
		if (context instanceof MQTTContext) {
			MQTTContext ctx = (MQTTContext) context;
			if (ctx.getQoS() != null) {
				qos = ctx.getQoS();
			}
			retained = ctx.isRetained();
		}
		Promise<Message> promise = mqtt.subscribe(context.getReplyAddress(), qos.ordinal());
		mqtt.publish(topic, content.array(), qos.ordinal(), retained);
		return promise;
	}	
	private String generateClientId() {
		return "gecko-rpc-" + UUID.randomUUID().toString();
	}
}
