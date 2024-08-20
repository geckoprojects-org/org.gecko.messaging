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

package org.gecko.adapter.amqp.tests;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.gecko.adapter.amqp.client.AMQPClient;
import org.gecko.adapter.amqp.client.AMQPContextBuilder;
import org.gecko.osgi.messaging.MessagingConstants;
import org.gecko.osgi.messaging.MessagingContext;
import org.gecko.osgi.messaging.MessagingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.BundleContext;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.cm.annotations.RequireConfigurationAdmin;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.common.annotation.InjectService;
import org.osgi.test.common.service.ServiceAware;
import org.osgi.test.junit5.cm.ConfigurationExtension;
import org.osgi.test.junit5.context.BundleContextExtension;

/**
 * test for 
 * @author mark
 * @since 27.11.2018
 */
@RequireConfigurationAdmin
@ExtendWith(MockitoExtension.class)
@ExtendWith(BundleContextExtension.class)
@ExtendWith(ConfigurationExtension.class)
public class AMQPComponentPublishTest {

	private String amqpHost = System.getProperty("amqp.host", "devel.data-in-motion.biz");
	private String brokerUrl = "amqp://demo:1234@" + amqpHost + ":5672/test";
	public static final String PUBLISH_FAN_TOPIC = "test_pfan";
	public static final String PUBLISH_DIR_TOPIC = "test_pdir";
	private AMQPClient checkClient;
	@InjectBundleContext
	BundleContext context;
	Configuration clientConfig;
	@InjectService
	ConfigurationAdmin configAdmin;

	@BeforeEach
	public void setup() throws Exception {
		checkClient = new AMQPClient(amqpHost);
	}

	@AfterEach
	public void teardown() throws Exception {
		checkClient.disconnect();
		if (clientConfig != null) {
			clientConfig.delete();
			clientConfig = null;
		}
	}

	/**
	 * Tests publishing a message
	 * @throws Exception
	 */
	@Test
	public void testPublishMessage(@InjectService(cardinality = 0) ServiceAware<MessagingService> msAware)throws Exception {

		assertTrue(msAware.isEmpty());
		clientConfig = getConfiguration("AMQPService");
		assertNotNull(clientConfig);

		String topic = "test_PublishMessage";
		String publishContent = "this is an AMQP test";

		// has to be a new configuration
		Dictionary<String, Object> p = clientConfig.getProperties();
		assertNull(p);
		// add service properties
		p = new Hashtable<>();
		p.put(MessagingConstants.PROP_BROKER, brokerUrl);

		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(1);
		// holder for the result
		AtomicReference<String> result = new AtomicReference<>();

		connectClient(topic, resultLatch, result);
				
		// starting adapter with the given properties
		clientConfig.update(p);
		
		// check for service
		MessagingService messagingService = msAware.waitForService(30000l);
		assertNotNull(messagingService);

		//send message and wait for the result
		messagingService.publish(topic, ByteBuffer.wrap(publishContent.getBytes()));

		// wait and compare the received message
		resultLatch.await(5, TimeUnit.SECONDS);
		assertEquals(publishContent, result.get());

	}
	
	/**
	 * Tests publishing a message using fanout multicast
	 * https://www.rabbitmq.com/tutorials/tutorial-four-java.html
	 * @throws Exception
	 */
	@Test
	public void testPublishFanoutMessage(@InjectService(cardinality = 0) ServiceAware<MessagingService> msAware) throws Exception {
		
		assertTrue(msAware.isEmpty());
		clientConfig = getConfiguration("AMQPService");
		
		String publishContent = "this is an AMQP test";
		
		// has to be a new configuration
		Dictionary<String, Object> p = clientConfig.getProperties();
		assertNull(p);
		// add service properties
		p = new Hashtable<>();
//		p.put(MessagingConstants.PROP_PUBLISH_TOPICS, publishTopic);
		p.put(MessagingConstants.PROP_BROKER, brokerUrl);
		
		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(2);
		// holder for the result
		AtomicReference<String> result01 = new AtomicReference<>();
		AtomicReference<String> result02 = new AtomicReference<>();
		
		checkClient.registerConsumerFanout(PUBLISH_FAN_TOPIC, (b)->{
			String r = new String(b);
			result01.set(r);
			resultLatch.countDown();
		});
		AMQPClient c2 = new AMQPClient(amqpHost);
		c2.registerConsumerFanout(PUBLISH_FAN_TOPIC, (b)->{
			String r = new String(b);
			result02.set(r);
			resultLatch.countDown();
		});
		
		// starting adapter with the given properties
		clientConfig.update(p);
		
		// check for service
		MessagingService messagingService = msAware.waitForService(2000l);
		assertNotNull(messagingService);
		
		//send message and wait for the result
		MessagingContext ctx = new AMQPContextBuilder().fanout().exchange(PUBLISH_FAN_TOPIC, "").build();
		messagingService.publish(PUBLISH_FAN_TOPIC, ByteBuffer.wrap(publishContent.getBytes()), ctx);
		
		// wait and compare the received message
		resultLatch.await(5, TimeUnit.SECONDS);
		assertEquals(publishContent, result01.get());
		assertEquals(publishContent, result02.get());
		
	}
	
	/**
	 * Tests publishing a message using fanout multicast
	 * https://www.rabbitmq.com/tutorials/tutorial-four-java.html
	 * @throws Exception
	 */
	@Test
	public void testPublishDirectMulticastMessage(@InjectService(cardinality = 0) ServiceAware<MessagingService> msAware) throws Exception {
		
		assertTrue(msAware.isEmpty());
		clientConfig = getConfiguration("AMQPService");
		
		String publishContent = "this is an AMQP test";
		
		// has to be a new configuration
		Dictionary<String, Object> p = clientConfig.getProperties();
		assertNull(p);
		// add service properties
		p = new Hashtable<>();
//		p.put(MessagingConstants.PROP_PUBLISH_TOPICS, publishTopic);
		p.put(MessagingConstants.PROP_BROKER, brokerUrl);
		
		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(2);
		// holder for the result
		AtomicReference<String> result01 = new AtomicReference<>();
		AtomicReference<String> result02 = new AtomicReference<>();
		
		checkClient.registerConsumerDirect(PUBLISH_DIR_TOPIC, "bla", (b)->{
			String r = new String(b);
			result01.set(r);
			resultLatch.countDown();
		});
		AMQPClient c2 = new AMQPClient(amqpHost);
		c2.registerConsumerDirect(PUBLISH_DIR_TOPIC, "bla", (b)->{
			String r = new String(b);
			result02.set(r);
			resultLatch.countDown();
		});
		
		// starting adapter with the given properties
		clientConfig.update(p);
		
		// check for service
		MessagingService messagingService = msAware.waitForService(2000l);
		assertNotNull(messagingService);
		
		//send message and wait for the result
		MessagingContext ctx = new AMQPContextBuilder().durable().direct().exchange(PUBLISH_DIR_TOPIC, "bla").build();
		messagingService.publish(PUBLISH_DIR_TOPIC, ByteBuffer.wrap(publishContent.getBytes()), ctx);
		
		// wait and compare the received message
		resultLatch.await(5, TimeUnit.SECONDS);
		assertEquals(publishContent, result01.get());
		assertEquals(publishContent, result02.get());
		
	}
	
	/**
	 * Tests publishing a message
	 * @throws Exception
	 */
	@Test
	public void testPublishMessageEnv(@InjectService(cardinality = 0) ServiceAware<MessagingService> msAware) throws Exception {
		
		assertTrue(msAware.isEmpty());
		clientConfig = getConfiguration("AMQPService");
		String publishTopic = "test_PublishMessageEnv";
		String publishContent = "this is an AMQP test";
		
		// has to be a new configuration
		Dictionary<String, Object> p = clientConfig.getProperties();
		assertNull(p);
		// add service properties
		p = new Hashtable<>();
//		p.put(MessagingConstants.PROP_PUBLISH_TOPICS, publishTopic);
		System.setProperty("AMQP_USER", "demo");
		System.setProperty("AMQP_PWD", "1234");
		p.put("username.env", "AMQP_USER");
		p.put("password.env", "AMQP_PWD");
		p.put("host", amqpHost);
		p.put("port", 5672);
		p.put("virtualHost", "test");
		
		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(1);
		// holder for the result
		AtomicReference<String> result = new AtomicReference<>();
		
		connectClient(publishTopic, resultLatch, result);
		
		// starting adapter with the given properties
		clientConfig.update(p);
		
		// check for service
		MessagingService messagingService = msAware.waitForService(2000l);
		assertNotNull(messagingService);
		
		//send message and wait for the result
		messagingService.publish(publishTopic, ByteBuffer.wrap(publishContent.getBytes()));
		
		// wait and compare the received message
		resultLatch.await(5, TimeUnit.SECONDS);
		assertEquals(publishContent, result.get());
		
	}
	
	/**
	 * Tests publishing a message
	 * @throws Exception
	 */
	@Test
	public void testPublishMessage_wrongQueue(@InjectService(cardinality = 0) ServiceAware<MessagingService> msAware) throws Exception {
		
		assertTrue(msAware.isEmpty());
		clientConfig = getConfiguration("AMQPService");
		
		String publishTopic = "test_queue2";
		String subscribeTopic = "test_q";
		String publishContent = "this is an AMQP test";
		
		// has to be a new configuration
		Dictionary<String, Object> p = clientConfig.getProperties();
		assertNull(p);
		// add service properties
		p = new Hashtable<>();
//		p.put(MessagingConstants.PROP_PUBLISH_TOPICS, publishTopic);
		p.put(MessagingConstants.PROP_BROKER, brokerUrl);
		
		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(1);
		// holder for the result
		AtomicReference<String> result = new AtomicReference<>();
		
		connectClient(subscribeTopic, resultLatch, result);
		
		// starting adapter with the given properties
		clientConfig.update(p);
		
		// check for service
		MessagingService messagingService = msAware.waitForService(2000l);
		assertNotNull(messagingService);
		
		//send message and wait for the result
		messagingService.publish(publishTopic, ByteBuffer.wrap(publishContent.getBytes()));
		
		// wait and compare the received message
		boolean countedDown = resultLatch.await(5, TimeUnit.SECONDS);
		assertFalse(countedDown);
		
		resultLatch = new CountDownLatch(1);
		connectClient(publishTopic, resultLatch, result);
		countedDown = resultLatch.await(5, TimeUnit.SECONDS);
		assertTrue(countedDown);
		
	}

	/**
	 * Creates a configuration with the configuration admin
	 * @param context the bundle context
	 * @param configId the configuration id
	 * @return the configuration
	 * @throws Exception
	 */
	private Configuration getConfiguration(String configId) throws Exception {

		// service lookup for configuration admin service
		Configuration clientConfig = configAdmin.getConfiguration(configId, "?");
		assertNotNull(clientConfig);

		return clientConfig;
	}

	/**
	 * Connects the check client to
	 * @param topic the topic to connect
	 * @param checkLatch the check latch to block
	 * @param resultContent the {@link AtomicReference} for the content
	 * @throws MqttException
	 */
	private void connectClient(String topic, CountDownLatch checkLatch, AtomicReference<String> resultContent) throws Exception {
		checkClient.registerConsumerQueue(topic, false, "tag_" + topic, new Consumer<byte[]>() {
			
			@Override
			public void accept(byte[] t) {
				String v = new String(t);
				resultContent.set(v);
				checkLatch.countDown();
			}
		});
	}
	
}
