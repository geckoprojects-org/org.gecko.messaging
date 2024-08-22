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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.gecko.adapter.amqp.client.AMQPClient;
import org.gecko.osgi.messaging.Message;
import org.gecko.osgi.messaging.MessagingConstants;
import org.gecko.osgi.messaging.MessagingReplyToService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.service.cm.annotations.RequireConfigurationAdmin;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.common.annotation.InjectService;
import org.osgi.test.common.annotation.Property;
import org.osgi.test.common.annotation.config.WithFactoryConfiguration;
import org.osgi.test.common.service.ServiceAware;
import org.osgi.test.junit5.cm.ConfigurationExtension;
import org.osgi.test.junit5.context.BundleContextExtension;
import org.osgi.test.junit5.service.ServiceExtension;
import org.osgi.util.promise.Promise;
import org.osgi.util.tracker.ServiceTracker;

@RequireConfigurationAdmin
@ExtendWith(MockitoExtension.class)
@ExtendWith(BundleContextExtension.class)
@ExtendWith(ServiceExtension.class)
@ExtendWith(ConfigurationExtension.class)
public class AMQPComponentReplyToServiceTest {

	private AMQPClient checkClient;
	@InjectBundleContext
	BundleContext context;

	@BeforeEach
	public void setup() throws Exception {
		checkClient = new AMQPClient();
	}

	@AfterEach
	public void teardown() throws Exception {
		checkClient.disconnect();
	}

	@Test
	@WithFactoryConfiguration(factoryPid = "AMQPReplyToService", location = "?", name = "ps", properties = {
			@Property(key = MessagingConstants.PROP_BROKER, value = "amqp://demo:1234@devel.data-in-motion.biz:5672/test") })
	public void testSingleReplyToMessage(
			@InjectService(cardinality = 0) ServiceAware<MessagingReplyToService> mrtsAware) throws Exception {
		String publishTopic = "test_rpc";
		String publishContent = "this is an AMQP test";

		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(1);
		// holder for the result
		AtomicReference<String> result = new AtomicReference<>();

		// check for service
		MessagingReplyToService messagingService = mrtsAware.waitForService(2000l);
		assertNotNull(messagingService);
		checkClient.registerRPCEcho(publishTopic);
		Promise<Message> subscribe = messagingService.publishSingle(publishTopic,
				ByteBuffer.wrap(publishContent.getBytes()));
		subscribe.thenAccept((m) -> {
			String r = new String(m.payload().array());
			result.set(r);
			resultLatch.countDown();
		});

		// wait and compare the received message
		resultLatch.await(15, TimeUnit.SECONDS);
		assertEquals(publishContent, result.get());
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
		@WithFactoryConfiguration(factoryPid = "AMQPReplyToService", location = "?", name = "ps", properties = {
				@Property(key = "username.env", value = "AMQP_USER"),
				@Property(key = "password.env", value = "AMQP_PWD"),
				@Property(key = "host", value = "devel.data-in-motion.biz"), 
				@Property(key = "port", value = "5672"),
				@Property(key = "virtualHost", value = "test") })
		public void testSingleReplyToMessageEnv(
				@InjectService(cardinality = 0) ServiceAware<MessagingReplyToService> mrtsAware) throws Exception {

			String publishTopic = "test_rpcenv";
			String publishContent = "this is an AMQP test";

			// count down latch to wait for the message
			CountDownLatch resultLatch = new CountDownLatch(1);
			// holder for the result
			AtomicReference<String> result = new AtomicReference<>();

			// check for service
			MessagingReplyToService messagingService = mrtsAware.waitForService(2000l);
			assertNotNull(messagingService);
			checkClient.registerRPCEcho(publishTopic);
			Promise<Message> subscribe = messagingService.publishSingle(publishTopic,
					ByteBuffer.wrap(publishContent.getBytes()));
			subscribe.thenAccept((m) -> {
				String r = new String(m.payload().array());
				result.set(r);
				resultLatch.countDown();
			});

			// wait and compare the received message
			resultLatch.await(15, TimeUnit.SECONDS);
			assertEquals(publishContent, result.get());
		}
	}

	<T> T getService(Class<T> clazz, long timeout) throws InterruptedException {
		ServiceTracker<T, T> tracker = new ServiceTracker<>(context, clazz, null);
		tracker.open();
		return tracker.waitForService(timeout);
	}

	<T> T getService(Filter filter, long timeout) throws InterruptedException {
		ServiceTracker<T, T> tracker = new ServiceTracker<>(context, filter, null);
		tracker.open();
		return tracker.waitForService(timeout);
	}

}
