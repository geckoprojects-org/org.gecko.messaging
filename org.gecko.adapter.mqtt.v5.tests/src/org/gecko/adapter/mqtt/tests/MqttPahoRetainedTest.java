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

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptionsBuilder;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSecurityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MqttPahoRetainedTest {
	private static final Logger LOGGER = Logger.getLogger( MqttPahoRetainedTest.class.getName() );

//	private static final String TOPIC = "test.candelete/" + UUID.randomUUID() + "/";
	private static final String TOPIC1 = "test.candelete1/";
	private static final String TOPIC2 = "test.candelete2/";
	private static final String TOPIC3 = "test.candelete3/";
	private static final String TOPIC4 = "test.candelete4/";
	private static final int MESSAGE_COUNT = 1000;
	private static final String BROKER_URL = "tcp://datainmotion.de:1883";
//	private static final int MESSAGE_COUNT = 20000;
//	private static final String BROKER_URL = "tcp://localhost:1883";
//	private static final int MESSAGE_COUNT = 1000;
//	private static final String BROKER_URL = "tcp://localhost:2883";
//	AtomicInteger counter = new AtomicInteger();
//System.out.println(counter.incrementAndGet()+" - " + new String(message.getPayload()));

	@BeforeEach
	public void setUp() throws Exception {
		MqttClient mqtt = createClient(UUID.randomUUID().toString());
		try {
			String x = "1234567890-1234567890-1234567890-1234567890-1234567890-1234567890-1234567890-1234567890-1234567890-1234567890-123456789-";
			StringBuilder sb = new StringBuilder();
			for (int j = 0; j < 1; j++) {
				sb.append(x);
			}
			for (int i = 0; i < MESSAGE_COUNT; i++) {
				if(i % 1000 == 0)
					System.out.println("published "+i);
				String topic = TOPIC1 + "1234567890-1234567890-1234567890-" + i;
				mqtt.publish(topic, (sb.toString() + i).getBytes(), 0, true);
			}
			for (int i = 0; i < MESSAGE_COUNT; i++) {
				if(i % 1000 == 0)
					System.out.println("published "+i);
				String topic = TOPIC2 + "1234567890-1234567890-1234567890-" + i;
				mqtt.publish(topic, (sb.toString() + i).getBytes(), 0, true);
			}
			for (int i = 0; i < MESSAGE_COUNT; i++) {
				if(i % 1000 == 0)
					System.out.println("published "+i);
				String topic = TOPIC3 + "1234567890-1234567890-1234567890-" + i;
				mqtt.publish(topic, (sb.toString() + i).getBytes(), 0, true);
			}
			for (int i = 0; i < MESSAGE_COUNT; i++) {
				if(i % 1000 == 0)
					System.out.println("published "+i);
				String topic = TOPIC4 + "1234567890-1234567890-1234567890-" + i;
				mqtt.publish(topic, (sb.toString() + i).getBytes(), 0, true);
			}

		} finally {
			mqtt.disconnect();
			mqtt.close();
		}
	}

	@Test
	public void testSubscribeRetained() throws Exception {
		MqttClient mqtt = createClient(UUID.randomUUID().toString());
		MqttClient mqtt2 = createClient(UUID.randomUUID().toString());
		try {
			sub(TOPIC1, mqtt,mqtt2);
			sub(TOPIC2, mqtt,mqtt2);
			sub(TOPIC3, mqtt,mqtt2);
			sub(TOPIC4, mqtt,mqtt2);
			System.out.println();
		} catch (Exception e) {
			LOGGER.log( Level.SEVERE, e.getMessage(), e );
		} finally {
			mqtt.disconnect();
			mqtt.close();
		}
	}

	private void sub(String topic, MqttClient mqtt, MqttClient mqtt2) throws MqttException, MqttSecurityException, InterruptedException {
		CountDownLatch messageLatch = new CountDownLatch(MESSAGE_COUNT);
		mqtt.subscribe(topic + "#", 1, new IMqttMessageListener() {

			@Override
			public void messageArrived(String topic, MqttMessage message) throws Exception {
				LOGGER.log( Level.INFO, topic);
				try {
					mqtt2.publish("g6/"+topic, message);
				} catch (Exception e) {
					 LOGGER.log( Level.SEVERE, e.getMessage(), e );
				}
				messageLatch.countDown();
			}
		});

//		boolean result = messageLatch.await(10, TimeUnit.SECONDS);
//		assertTrue(result, "Expected 0 but was " + messageLatch.getCount());
	}

	private MqttClient createClient(String id) throws MqttException, MqttSecurityException {
		MqttConnectionOptionsBuilder ob = new MqttConnectionOptionsBuilder();
		ob.username("demo");
		ob.password("1234".getBytes());
		ob.automaticReconnect(true);
		ob.maximumPacketSize(4294967295l);
		ob.requestProblemInfo(true);
		ob.keepAliveInterval(10);
		MqttClient mqtt = new MqttClient(BROKER_URL, id);
		mqtt.connect(ob.build());
		return mqtt;
	}
}
