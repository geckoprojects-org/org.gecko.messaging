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

package org.gecko.adapter.mqtt.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.ByteBuffer;

import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptionsBuilder;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.gecko.adapter.mqtt.MQTTContextBuilder;
import org.gecko.adapter.mqtt.QoS;
import org.gecko.moquette.broker.MQTTBroker;
import org.gecko.osgi.messaging.Message;
import org.gecko.osgi.messaging.MessagingConstants;
import org.gecko.osgi.messaging.MessagingContext;
import org.gecko.osgi.messaging.MessagingRPCService;
import org.gecko.osgi.messaging.annotations.RequireRPCv5;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.test.common.annotation.InjectService;
import org.osgi.test.common.annotation.Property;
import org.osgi.test.common.annotation.config.WithFactoryConfiguration;
import org.osgi.test.junit5.cm.ConfigurationExtension;
import org.osgi.test.junit5.service.ServiceExtension;
import org.osgi.util.promise.Promise;

@ExtendWith(MockitoExtension.class)
@ExtendWith(ServiceExtension.class)
@ExtendWith(ConfigurationExtension.class)
@RequireRPCv5
@WithFactoryConfiguration(factoryPid = "MQTTBroker", location = "?", name = "broker", properties = {
		@Property(key = MQTTBroker.HOST, value = "localhost"), //
		@Property(key = MQTTBroker.PORT, value = "2183") })
public class MqttRPCComponentTest {

	private static final String BROKER_URL = "tcp://localhost:2183";
	private MqttClient checkClient;

	
	@AfterEach
	public void teardown() throws MqttException {
		if (checkClient != null) {
			if (checkClient.isConnected()) {
				checkClient.disconnect();
			}
			checkClient.close();
		}
	}
	
	@Test
	@WithFactoryConfiguration(factoryPid = "MQTTRPCServiceV5", location = "?", name = "read", properties = {
			@Property(key = MessagingConstants.PROP_BROKER, value = BROKER_URL) })
	public void testPublish(@InjectService MQTTBroker broker,
			@InjectService(timeout = 1500) MessagingRPCService messagingService) throws Exception {

		String publishTopic = "testv5.rpc";
		String publishContent = "This is test content";

		
		//send message and wait for the result
		Promise<Message> promise = messagingService.publishRPC(publishTopic,ByteBuffer.wrap(publishContent.getBytes()));
		assertNotNull(promise);
		Message value = promise.timeout(10000).getValue();
		assertEquals(publishContent, new String(value.payload().array()));
	}

	@Test
	@WithFactoryConfiguration(factoryPid = "MQTTRPCServiceV5", location = "?", name = "read", properties = {
			@Property(key = MessagingConstants.PROP_BROKER, value = BROKER_URL) })
	public void testPublishDiffReplyTo(@InjectService MQTTBroker broker,
			@InjectService(timeout = 1500) MessagingRPCService messagingService) throws Exception {
		
		String publishTopic = "testv5.rpc";
		String publishContent = "This is test content";
		String replyTopic = "testv5.rpc.response";
		
		forward(publishTopic, replyTopic);
		
		MessagingContext ctx = new MQTTContextBuilder().withQoS(QoS.AT_LEAST_ONE).replyTo(replyTopic).build();
		//send message and wait for the result
		Promise<Message> promise = messagingService.publishRPC(publishTopic,ByteBuffer.wrap(publishContent.getBytes()), ctx);
		assertNotNull(promise);
		Message value = promise.timeout(10000).getValue();
		assertEquals(publishContent + " response", new String(value.payload().array()));
	}
	
		
	private void forward(String sourceTopic, String targetTopic) throws MqttException {
		checkClient = new MqttClient(BROKER_URL, "test");
		MqttConnectionOptionsBuilder ob = new MqttConnectionOptionsBuilder();
		ob.username("demo");
		ob.password("1234".getBytes());
		checkClient.connect(ob.build());
		checkClient.subscribe(sourceTopic, QoS.AT_LEAST_ONE.ordinal(), new IMqttMessageListener() {
			
			@Override
			public void messageArrived(String topic, MqttMessage message) throws Exception {
				String c = new String(message.getPayload())+ " response";
				message.setPayload(c.getBytes());
				checkClient.publish(targetTopic, message);
			}
		});
	}
		
}
