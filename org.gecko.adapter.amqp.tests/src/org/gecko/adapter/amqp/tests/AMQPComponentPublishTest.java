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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.service.cm.annotations.RequireConfigurationAdmin;
import org.osgi.test.common.annotation.InjectService;
import org.osgi.test.common.annotation.Property;
import org.osgi.test.common.annotation.config.WithFactoryConfiguration;
import org.osgi.test.common.service.ServiceAware;
import org.osgi.test.junit5.cm.ConfigurationExtension;
import org.osgi.test.junit5.context.BundleContextExtension;

@RequireConfigurationAdmin
@ExtendWith(MockitoExtension.class)
@ExtendWith(BundleContextExtension.class)
@ExtendWith(ConfigurationExtension.class)
public class AMQPComponentPublishTest {

	private static final String amqpHost = "devel.data-in-motion.biz";
	private static final String PUBLISH_FAN_TOPIC = "test_pfan";
	private static final String PUBLISH_DIR_TOPIC = "test_pdir";
	private AMQPClient checkClient;

	@BeforeEach
	public void setup() throws Exception {
		checkClient = new AMQPClient(amqpHost);
	}

	@AfterEach
	public void teardown() throws Exception {
		checkClient.disconnect();
	}

	/**
	 * Tests publishing a message
	 * 
	 * @throws Exception
	 */
	@Test
	@WithFactoryConfiguration(factoryPid = "AMQPService", location = "?", name = "ps", properties = {
			@Property(key = MessagingConstants.PROP_BROKER, value = "amqp://demo:1234@devel.data-in-motion.biz:5672/test") })
	public void testPublishMessage(@InjectService(cardinality = 0) ServiceAware<MessagingService> msAware)
			throws Exception {
		String topic = "test_PublishMessage";
		String publishContent = "this is an AMQP test";

		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(1);
		// holder for the result
		AtomicReference<String> result = new AtomicReference<>();
		connectClient(topic, resultLatch, result);

		// check for service
		MessagingService messagingService = msAware.waitForService(30000l);
		assertNotNull(messagingService);

		// send message and wait for the result
		messagingService.publish(topic, ByteBuffer.wrap(publishContent.getBytes()));

		// wait and compare the received message
		resultLatch.await(5, TimeUnit.SECONDS);
		assertEquals(publishContent, result.get());
	}

	/**
	 * Tests publishing a message using fanout multicast
	 * https://www.rabbitmq.com/tutorials/tutorial-four-java.html
	 * 
	 * @throws Exception
	 */
	@Test
	@WithFactoryConfiguration(factoryPid = "AMQPService", location = "?", name = "ps", properties = {
			@Property(key = MessagingConstants.PROP_BROKER, value = "amqp://demo:1234@devel.data-in-motion.biz:5672/test") })
	public void testPublishFanoutMessage(@InjectService(cardinality = 0) ServiceAware<MessagingService> msAware)
			throws Exception {

		String publishContent = "this is an AMQP test";

		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(2);
		// holder for the result
		AtomicReference<String> result01 = new AtomicReference<>();
		AtomicReference<String> result02 = new AtomicReference<>();

		checkClient.registerConsumerFanout(PUBLISH_FAN_TOPIC, (b) -> {
			String r = new String(b);
			result01.set(r);
			resultLatch.countDown();
		});
		AMQPClient c2 = new AMQPClient(amqpHost);
		c2.registerConsumerFanout(PUBLISH_FAN_TOPIC, (b) -> {
			String r = new String(b);
			result02.set(r);
			resultLatch.countDown();
		});

		// check for service
		MessagingService messagingService = msAware.waitForService(2000l);
		assertNotNull(messagingService);

		// send message and wait for the result
		MessagingContext ctx = new AMQPContextBuilder().fanout().exchange(PUBLISH_FAN_TOPIC, "").build();
		messagingService.publish(PUBLISH_FAN_TOPIC, ByteBuffer.wrap(publishContent.getBytes()), ctx);

		// wait and compare the received message
		resultLatch.await(5, TimeUnit.SECONDS);
		assertEquals(publishContent, result01.get());
		assertEquals(publishContent, result02.get());
	}

	@Test
	@WithFactoryConfiguration(factoryPid = "AMQPService", location = "?", name = "ps", properties = {
			@Property(key = MessagingConstants.PROP_BROKER, value = "amqp://demo:1234@devel.data-in-motion.biz:5672/test") })
	public void testPublishDirectMulticastMessage(
			@InjectService(cardinality = 0) ServiceAware<MessagingService> msAware) throws Exception {

		String publishContent = "this is an AMQP test";

		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(2);
		// holder for the result
		AtomicReference<String> result01 = new AtomicReference<>();
		AtomicReference<String> result02 = new AtomicReference<>();

		checkClient.registerConsumerDirect(PUBLISH_DIR_TOPIC, "bla", (b) -> {
			String r = new String(b);
			result01.set(r);
			resultLatch.countDown();
		});
		AMQPClient c2 = new AMQPClient(amqpHost);
		c2.registerConsumerDirect(PUBLISH_DIR_TOPIC, "bla", (b) -> {
			String r = new String(b);
			result02.set(r);
			resultLatch.countDown();
		});

		// check for service
		MessagingService messagingService = msAware.waitForService(2000l);
		assertNotNull(messagingService);

		// send message and wait for the result
		MessagingContext ctx = new AMQPContextBuilder().durable().direct().exchange(PUBLISH_DIR_TOPIC, "bla").build();
		messagingService.publish(PUBLISH_DIR_TOPIC, ByteBuffer.wrap(publishContent.getBytes()), ctx);

		// wait and compare the received message
		resultLatch.await(5, TimeUnit.SECONDS);
		assertEquals(publishContent, result01.get());
		assertEquals(publishContent, result02.get());
	}

	@Nested
	class Env {

		@BeforeEach
		public void setup() throws Exception {
			System.setProperty("AMQP_USER", "demo");
			System.setProperty("AMQP_PWD", "1234");
		}

		@AfterEach
		public void teardown() throws Exception {
			System.clearProperty("AMQP_USER");
			System.clearProperty("AMQP_PWD");
		}

		@Test
		@WithFactoryConfiguration(factoryPid = "AMQPService", location = "?", name = "ps", properties = {
				@Property(key = "username.env", value = "AMQP_USER"),
				@Property(key = "password.env", value = "AMQP_PWD"),
				@Property(key = "host", value = "devel.data-in-motion.biz"), @Property(key = "port", value = "5672"),
				@Property(key = "virtualHost", value = "test") })
		public void testPublishMessageEnv(@InjectService(cardinality = 0) ServiceAware<MessagingService> msAware)
				throws Exception {

			String publishTopic = "test_PublishMessageEnv";
			String publishContent = "this is an AMQP test";
			// count down latch to wait for the message
			CountDownLatch resultLatch = new CountDownLatch(1);
			// holder for the result
			AtomicReference<String> result = new AtomicReference<>();

			connectClient(publishTopic, resultLatch, result);

			// check for service
			MessagingService messagingService = msAware.waitForService(2000l);
			assertNotNull(messagingService);

			// send message and wait for the result
			messagingService.publish(publishTopic, ByteBuffer.wrap(publishContent.getBytes()));

			// wait and compare the received message
			resultLatch.await(5, TimeUnit.SECONDS);
			assertEquals(publishContent, result.get());

		}
	}

	@Test
	@WithFactoryConfiguration(factoryPid = "AMQPService", location = "?", name = "ps", properties = {
			@Property(key = MessagingConstants.PROP_BROKER, value = "amqp://demo:1234@devel.data-in-motion.biz:5672/test") })
	public void testPublishMessage_wrongQueue(@InjectService(cardinality = 0) ServiceAware<MessagingService> msAware)
			throws Exception {

		String publishTopic = "test_queue2";
		String subscribeTopic = "test_wrongQueue";
		String publishContent = "this is an AMQP test";

		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(1);
		// holder for the result
		AtomicReference<String> result = new AtomicReference<>();
		connectClient(subscribeTopic, resultLatch, result);

		// check for service
		MessagingService messagingService = msAware.waitForService(2000l);
		assertNotNull(messagingService);

		// send message and wait for the result
		messagingService.publish(publishTopic, ByteBuffer.wrap(publishContent.getBytes()));

		// wait and compare the received message
		assertFalse(resultLatch.await(5, TimeUnit.SECONDS));

		resultLatch = new CountDownLatch(1);
		connectClient(publishTopic, resultLatch, result);
		assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
	}

	/**
	 * Connects the check client to
	 * 
	 * @param topic         the topic to connect
	 * @param checkLatch    the check latch to block
	 * @param resultContent the {@link AtomicReference} for the content
	 * @throws MqttException
	 */
	private void connectClient(String topic, CountDownLatch checkLatch, AtomicReference<String> resultContent)
			throws Exception {
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
