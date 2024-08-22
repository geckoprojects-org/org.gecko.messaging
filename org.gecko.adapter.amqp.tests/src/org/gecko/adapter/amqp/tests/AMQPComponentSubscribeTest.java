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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.gecko.adapter.amqp.client.AMQPClient;
import org.gecko.adapter.amqp.client.AMQPContextBuilder;
import org.gecko.osgi.messaging.Message;
import org.gecko.osgi.messaging.MessagingConstants;
import org.gecko.osgi.messaging.MessagingContext;
import org.gecko.osgi.messaging.MessagingService;
import org.gecko.util.pushstream.PushStreamConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.service.cm.annotations.RequireConfigurationAdmin;
import org.osgi.test.common.annotation.InjectService;
import org.osgi.test.common.annotation.Property;
import org.osgi.test.common.annotation.Property.Scalar;
import org.osgi.test.common.annotation.config.WithFactoryConfiguration;
import org.osgi.test.common.service.ServiceAware;
import org.osgi.test.junit5.cm.ConfigurationExtension;
import org.osgi.test.junit5.context.BundleContextExtension;
import org.osgi.test.junit5.service.ServiceExtension;
import org.osgi.util.promise.Promise;
import org.osgi.util.promise.PromiseFactory;
import org.osgi.util.pushstream.PushStream;

@RequireConfigurationAdmin
@ExtendWith(MockitoExtension.class)
@ExtendWith(BundleContextExtension.class)
@ExtendWith(ServiceExtension.class)
@ExtendWith(ConfigurationExtension.class)
public class AMQPComponentSubscribeTest {

	private String amqpHost = System.getProperty("amqp.host", "devel.data-in-motion.biz");
	private AMQPClient checkClient;

	@BeforeEach
	public void setup() throws Exception {
		checkClient = new AMQPClient();
	}

	@AfterEach
	public void teardown() throws Exception {
		checkClient.purgeChannel("test_#");
		checkClient.disconnect();
	}

	@Test
	@WithFactoryConfiguration(factoryPid = "AMQPService", location = "?", name = "ps", properties = {
			@Property(key = MessagingConstants.PROP_BROKER, value = "amqp://demo:1234@devel.data-in-motion.biz:5672/test") })
	public void testSubscribeMessage(@InjectService(cardinality = 0) ServiceAware<MessagingService> msAware)
			throws Exception {
		String publishTopic = "test_SubscribeMessage";
		String publishContent = "this is an AMQP test";

		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(1);
		// holder for the result
		AtomicReference<String> result = new AtomicReference<>();

		// check for service
		MessagingService messagingService = msAware.waitForService(2000l);
		assertNotNull(messagingService);
		PushStream<Message> subscribe = messagingService.subscribe(publishTopic);
		subscribe.forEach((m) -> {
			String r = new String(m.payload().array());
			result.set(r);
			resultLatch.countDown();
		});

		checkClient.sendSingleWithQueue(publishTopic, publishContent);
		// wait and compare the received message
		resultLatch.await(15, TimeUnit.SECONDS);
		assertEquals(publishContent, result.get());
	}

	/**
	 * Tests subscribing to a message using fanout multicast
	 * https://www.rabbitmq.com/tutorials/tutorial-four-java.html
	 * 
	 * @throws Exception
	 */
	@Test
	@WithFactoryConfiguration(factoryPid = "AMQPService", location = "?", name = "ps", properties = {
			@Property(key = MessagingConstants.PROP_BROKER, value = "amqp://demo:1234@devel.data-in-motion.biz:5672/test") })
	public void testSubscribeFanout(@InjectService(cardinality = 0) ServiceAware<MessagingService> msAware)
			throws Exception {
		String publishTopic = "test_SubscribeFanout";
		String publishContent = "this is an AMQP test";

		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(2);
		// holder for the result
		AtomicReference<String> result01 = new AtomicReference<>();
		AtomicReference<String> result02 = new AtomicReference<>();

		// check for service
		MessagingService messagingService = msAware.waitForService(2000l);
		assertNotNull(messagingService);
		MessagingContext ctx = new AMQPContextBuilder().fanout().exchange(publishTopic, "").build();
		PushStream<Message> subscribe01 = messagingService.subscribe(publishTopic, ctx);
		subscribe01.forEach((m) -> {
			String r = new String(m.payload().array());
			result01.set(r);
			resultLatch.countDown();
		});
		AMQPClient c2 = new AMQPClient(amqpHost);
		c2.registerConsumerFanout(publishTopic, (b) -> {
			String r = new String(b);
			result02.set(r);
			resultLatch.countDown();
		});

		checkClient.sendSingleWithFanout(publishTopic, publishContent);
		// wait and compare the received message
		assertTrue(resultLatch.await(15, TimeUnit.SECONDS));
		assertEquals(publishContent, result01.get());
		assertEquals(publishContent, result02.get());
	}

	@Test
	@WithFactoryConfiguration(factoryPid = "AMQPService", location = "?", name = "ps", properties = {
			@Property(key = MessagingConstants.PROP_BROKER, value = "amqp://demo:1234@devel.data-in-motion.biz:5672/test") })
	public void testSubscribeDirectMulticast(@InjectService(cardinality = 0) ServiceAware<MessagingService> msAware)
			throws Exception {
		String publishTopic = "test_SubscribeDirectMulticast";
		String publishContent = "this is an AMQP test";

		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(2);
		// holder for the result
		AtomicReference<String> result01 = new AtomicReference<>();
		AtomicReference<String> result02 = new AtomicReference<>();

		MessagingService messagingService = msAware.waitForService(2000l);

		assertNotNull(messagingService);
		MessagingContext ctx = new AMQPContextBuilder().durable().direct().exchange(publishTopic, "blub").build();
		PushStream<Message> subscribe01 = messagingService.subscribe(publishTopic, ctx);
		subscribe01.forEach((m) -> {
			String r = new String(m.payload().array());
			result01.set(r);
			resultLatch.countDown();
		});
		AMQPClient c2 = new AMQPClient(amqpHost);
		c2.registerConsumerDirect(publishTopic, "blub", (b) -> {
			String r = new String(b);
			result02.set(r);
			resultLatch.countDown();
		});

		checkClient.sendSingleWithExchangeDirect(publishTopic, "blub", publishContent);
		// wait and compare the received message
		assertTrue(resultLatch.await(15, TimeUnit.SECONDS));
		assertEquals(publishContent, result01.get());
		assertEquals(publishContent, result02.get());
	}

	@Test
	@WithFactoryConfiguration(factoryPid = "AMQPService", location = "?", name = "ps", properties = {
			@Property(key = MessagingConstants.PROP_BROKER, value = "amqp://demo:1234@devel.data-in-motion.biz:5672/test") })
	public void testSubscribeDirectSelective(@InjectService(cardinality = 0) ServiceAware<MessagingService> msAware)
			throws Exception {
		String publishTopic = "test_SubscribeDirectSelective";
		String publishContent = "this is an AMQP test";

		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(1);
		// holder for the result
		AtomicReference<String> result01 = new AtomicReference<>();
		AtomicReference<String> result02 = new AtomicReference<>();

		// check for service
		MessagingService messagingService = msAware.waitForService(2000l);
		assertNotNull(messagingService);
		MessagingContext ctx = new AMQPContextBuilder().durable().direct().exchange(publishTopic, "bla").build();
		PushStream<Message> subscribe01 = messagingService.subscribe(publishTopic, ctx);
		subscribe01.forEach((m) -> {
			String r = new String(m.payload().array());
			result01.set(r);
			resultLatch.countDown();
		});
		AMQPClient c2 = new AMQPClient(amqpHost);
		c2.registerConsumerDirect(publishTopic, "blub", (b) -> {
			String r = new String(b);
			result02.set(r);
			resultLatch.countDown();
		});

		checkClient.sendSingleWithExchangeDirect(publishTopic, "blub", publishContent);
		// wait and compare the received message
		assertTrue(resultLatch.await(15, TimeUnit.SECONDS));
		assertNull(result01.get());
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
				@Property(key = "host", value = "devel.data-in-motion.biz"),
				@Property(key = "port", value = "5672"),
				@Property(key = "virtualHost", value = "test") })
		public void testSubscribeMessageEnv(@InjectService(cardinality = 0) ServiceAware<MessagingService> msAware)
				throws Exception {
			String publishTopic = "test_SubscribeMessageEnv";
			String publishContent = "this is an AMQP test";

			// count down latch to wait for the message
			CountDownLatch resultLatch = new CountDownLatch(1);
			// holder for the result
			AtomicReference<String> result = new AtomicReference<>();

			// check for service
			MessagingService messagingService = msAware.waitForService(2000l);
			assertNotNull(messagingService);
			PushStream<Message> subscribe = messagingService.subscribe(publishTopic);
			subscribe.forEach((m) -> {
				String r = new String(m.payload().array());
				result.set(r);
				resultLatch.countDown();
			});

			checkClient.sendSingleWithQueue(publishTopic, publishContent);
			// wait and compare the received message
			resultLatch.await(15, TimeUnit.SECONDS);
			assertEquals(publishContent, result.get());
		}
	}

	@Test
	@WithFactoryConfiguration(factoryPid = "AMQPService", location = "?", name = "ps", properties = {
			@Property(key = MessagingConstants.PROP_BROKER, value = "amqp://demo:1234@devel.data-in-motion.biz:5672/test"),
			@Property(key = PushStreamConstants.PROP_SES_BUFFER_SIZE, value = "100", scalar = Scalar.Integer),
			@Property(key = PushStreamConstants.PROP_SES_QUEUE_POLICY_BY_NAME, value = "BLOCK")
	})
	public void testSubscribeMessageConfigureEventSource(
			@InjectService(cardinality = 0) ServiceAware<MessagingService> msAware) throws Exception {

		String publishTopic = "test_SubscribeMessageConfigureEventSource";
		String publishContent = "this is an AMQP test";


		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(1);
		// holder for the result
		AtomicReference<String> result = new AtomicReference<>();

		// check for service
		MessagingService messagingService = msAware.waitForService(2000l);
		assertNotNull(messagingService);
		PushStream<Message> subscribe = messagingService.subscribe(publishTopic);
		subscribe.forEach((m) -> {
			String r = new String(m.payload().array());
			result.set(r);
			resultLatch.countDown();
		});

		checkClient.sendSingleWithQueue(publishTopic, publishContent);
		// wait and compare the received message
		resultLatch.await(15, TimeUnit.SECONDS);
		assertEquals(publishContent, result.get());
	}

	@Test
	@WithFactoryConfiguration(factoryPid = "AMQPService", location = "?", name = "ps", properties = {
			@Property(key = MessagingConstants.PROP_BROKER, value = "amqp://demo:1234@devel.data-in-motion.biz:5672/test"),
			@Property(key = "jmx", value = "true")
	})
	public void testSubscribeMessageMany(@InjectService(cardinality = 0) ServiceAware<MessagingService> msAware)
			throws Exception {
		String publishTopic = "test_SubscribeMessageMany";
		String publishContent = "this is an AMQP test";

		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(10);
		// holder for the result
		AtomicReference<List<String>> result = new AtomicReference<>();
		result.set(new ArrayList<String>(10));

		// check for service
		MessagingService messagingService = msAware.waitForService(2000l);
		assertNotNull(messagingService);

		PushStream<Message> subscribe = messagingService.subscribe(publishTopic);
		subscribe.forEach((m) -> {
			String r = new String(m.payload().array());
			result.get().add(r);
			resultLatch.countDown();
		});

		for (int i = 0; i < 10; i++) {
			checkClient.sendSingleWithQueue(publishTopic, publishContent + i);
		}

		// wait and compare the received message
		resultLatch.await(25, TimeUnit.SECONDS);
		assertEquals(10, result.get().size());
		assertEquals(publishContent + "9", result.get().get(9));
	}

	/**
	 * Tests publishing a message
	 * 
	 * @throws Exception
	 */
	@Test
	@WithFactoryConfiguration(factoryPid = "AMQPService", location = "?", name = "ps", properties = {
			@Property(key = MessagingConstants.PROP_BROKER, value = "amqp://demo:1234@devel.data-in-motion.biz:5672/test") })
	public void testSubscribeManyMessageMany(@InjectService(cardinality = 0) ServiceAware<MessagingService> msAware)
			throws Exception {
		String publishTopic = "test_SubscribeManyMessageManyr";
		String publishContent = "this is an AMQP test";

		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(20);
		// holder for the result
		AtomicReference<List<String>> result = new AtomicReference<>();
		result.set(new ArrayList<String>(20));

		// check for service
		MessagingService messagingService = msAware.waitForService(2000l);
		assertNotNull(messagingService);
		AMQPContextBuilder builder = new AMQPContextBuilder();
		ExecutorService es = Executors.newCachedThreadPool();
		MessagingContext ctx = builder.direct().exchange(publishTopic, "test").durable().withParallelism(2)
				.withExecutor(es).build();
		PushStream<Message> subscribe01 = messagingService.subscribe(publishTopic, ctx);
		ctx = builder.direct().exchange(publishTopic, "test").durable().withParallelism(2).withExecutor(es).build();
		PushStream<Message> subscribe02 = messagingService.subscribe(publishTopic, ctx);

//		PromiseFactory pf = new PromiseFactory(es);
//		Promise<Integer> countPromise = pf.submit(()->{
//			if (resultLatch.await(10, TimeUnit.SECONDS) ) {
//				return result.get().size();
//			} else {
//				throw new IllegalStateException("Timeout waiting");
//			}
//		});

		subscribe01.forEach((m) -> {
			String r = new String(m.payload().array());
			List<String> list = result.get();
			synchronized (list) {
				list.add(r + "1");
			}
			resultLatch.countDown();
		});
		subscribe02.forEach((m) -> {
			String r = new String(m.payload().array());
			List<String> list = result.get();
			synchronized (list) {
				list.add(r + "2");
			}
			resultLatch.countDown();
		});

		PromiseFactory pf = new PromiseFactory(es);
		Promise<Integer> sendPromise = pf.submit(() -> {
			for (int i = 0; i < 10; i++) {
				checkClient.sendSingleWithExchangeDirect(publishTopic, "test", publishContent + i);
			}
			return 10;
		});
		assertEquals(10, sendPromise.getValue().intValue());
		resultLatch.await(10, TimeUnit.SECONDS);
		assertEquals(20, result.get().size());
//		countPromise.thenAccept((i)->assertEquals(20, i.intValue())).onFailure(t->fail("Test failed with " + t));
		// wait and compare the received message
//		countPromise.getValue();
	}

	/**
	 * Tests publishing a message
	 * 
	 * @throws Exception
	 */
	@Test
	@WithFactoryConfiguration(factoryPid = "AMQPService", location = "?", name = "ps", properties = {
			@Property(key = MessagingConstants.PROP_BROKER, value = "amqp://demo:1234@devel.data-in-motion.biz:5672/test") })
	public void testSubscribeMessageWrongQueue(@InjectService(cardinality = 0) ServiceAware<MessagingService> msAware)
			throws Exception {
		String publishTopic = "test_SubscribeMessageWrongQueue";
		String publishContent = "this is an AMQP test";


		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(1);
		// holder for the result
		AtomicReference<String> result = new AtomicReference<>();

		// check for service
		MessagingService messagingService = msAware.waitForService(2000l);
		assertNotNull(messagingService);
		PushStream<Message> subscribe = messagingService.subscribe(publishTopic);
		subscribe.forEach((m) -> {
			String r = new String(m.payload().array());
			result.set(r);
			resultLatch.countDown();
		});

		checkClient.sendSingleWithQueue(publishTopic + "test", publishContent);
		// wait and compare the received message
		boolean countedDown = resultLatch.await(5, TimeUnit.SECONDS);
		assertFalse(countedDown);
		assertNull(result.get());
	}

}
