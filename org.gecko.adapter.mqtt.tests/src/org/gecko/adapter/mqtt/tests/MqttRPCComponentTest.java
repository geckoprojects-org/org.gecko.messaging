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

import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.gecko.adapter.mqtt.MQTTContextBuilder;
import org.gecko.adapter.mqtt.QoS;
import org.gecko.moquette.broker.MQTTBroker;
import org.gecko.osgi.messaging.Message;
import org.gecko.osgi.messaging.MessagingConstants;
import org.gecko.osgi.messaging.MessagingContext;
import org.gecko.osgi.messaging.MessagingRPCService;
import org.gecko.osgi.messaging.annotations.RequireMQTTv3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.BundleContext;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.common.annotation.InjectService;
import org.osgi.test.common.annotation.Property;
import org.osgi.test.common.annotation.config.WithFactoryConfiguration;
import org.osgi.test.junit5.cm.ConfigurationExtension;
import org.osgi.test.junit5.context.BundleContextExtension;
import org.osgi.test.junit5.service.ServiceExtension;
import org.osgi.util.promise.Promise;

@ExtendWith(MockitoExtension.class)
@ExtendWith(ServiceExtension.class)
@ExtendWith(ConfigurationExtension.class)
@ExtendWith(BundleContextExtension.class)
@RequireMQTTv3	
public class MqttRPCComponentTest {

	private static final String BROKER_URL = "tcp://localhost:2183";
	private MqttClient checkClient;

	@InjectBundleContext
	BundleContext bctx;
	
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
	@WithFactoryConfiguration(factoryPid = "MQTTBroker", location = "?", name = "broker", properties = {
			@Property(key = MQTTBroker.HOST, value = "localhost"), //
			@Property(key = MQTTBroker.PORT, value = "2183") })
	@WithFactoryConfiguration(factoryPid = "MQTTRPCService", location = "?", name = "rpc", properties = {
			@Property(key = MessagingConstants.PROP_BROKER, value = BROKER_URL) })
	public void testPublish(@InjectService MQTTBroker broker,
			@InjectService(timeout = 500) MessagingRPCService messagingService) throws Exception {
		String publishTopic = "testv3.rpc";
		String publishContent = "This is test content";
		
		//send message and wait for the result
		Promise<Message> promise = messagingService.publishRPC(publishTopic,ByteBuffer.wrap(publishContent.getBytes()));
		assertNotNull(promise);
		Message value = promise.timeout(10000).getValue();
		assertEquals(publishContent, new String(value.payload().array()));
	}

	@Test
	@WithFactoryConfiguration(factoryPid = "MQTTBroker", location = "?", name = "broker", properties = {
			@Property(key = MQTTBroker.HOST, value = "localhost"), //
			@Property(key = MQTTBroker.PORT, value = "2183") })
	@WithFactoryConfiguration(factoryPid = "MQTTRPCService", location = "?", name = "read", properties = {
			@Property(key = MessagingConstants.PROP_BROKER, value = BROKER_URL) })
	public void testPublishDiffReplyTo(@InjectService MQTTBroker broker,
			@InjectService(timeout = 500) MessagingRPCService messagingService) throws Exception {
		
		String publishTopic = "testv3.rpc";
		String publishContent = "This is test content";
		String replyTopic = "testv3.rpc.response";
		
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
		MqttConnectOptions ob = new MqttConnectOptions();
		ob.setUserName("demo");
		ob.setPassword("1234".toCharArray());
		checkClient.connect(ob);
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
