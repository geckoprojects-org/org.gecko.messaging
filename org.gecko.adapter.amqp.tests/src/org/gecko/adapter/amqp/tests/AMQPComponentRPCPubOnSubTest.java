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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.gecko.osgi.messaging.Message;
import org.gecko.osgi.messaging.MessagingConstants;
import org.gecko.osgi.messaging.MessagingRPCPubOnSub;
import org.gecko.osgi.messaging.MessagingRPCService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.common.annotation.InjectService;
import org.osgi.test.common.service.ServiceAware;
import org.osgi.test.junit5.context.BundleContextExtension;
import org.osgi.test.junit5.service.ServiceExtension;
import org.osgi.util.promise.Promise;

@ExtendWith(MockitoExtension.class)
@ExtendWith(BundleContextExtension.class)
@ExtendWith(ServiceExtension.class)
public class AMQPComponentRPCPubOnSubTest {

	private String amqpHost = System.getProperty("amqp.host", "devel.data-in-motion.biz");
	private String brokerUrl = "amqp://demo:1234@" + amqpHost + ":5672/test";
	private Configuration clientConfig = null;
	private Configuration serverConfig = null;
	@InjectBundleContext
	BundleContext context;
	@InjectService
	ConfigurationAdmin configAdmin;

	@BeforeEach
	public void setup() throws Exception {
	}

	@AfterEach
	public void teardown() throws Exception {
		//		if (serverConfig != null) {
		//			serverConfig.delete();
		//			serverConfig = null;
		//		}
		//		if (clientConfig != null) {
		//			clientConfig.delete();
		//			clientConfig = null;
		//		}
	}

	/**
	 * Tests publishing a message
	 * @throws Exception
	 * WORKS STANDALONE BUT NOT TOGETHER WITH PREVIOUS TEST?!
	 */
	@SuppressWarnings("rawtypes")
	//	@Test
	public void testRPCPubOnSubMessage(@InjectService(cardinality = 0) ServiceAware<MessagingRPCService> mrpcsAware,
			@InjectService(cardinality = 0) ServiceAware<MessagingRPCPubOnSub> mpossAware,
			@InjectService(cardinality = 0) ServiceAware<Function> functionAware) throws Exception {
		assertTrue(mpossAware.isEmpty());
		assertTrue(mrpcsAware.isEmpty());
		assertTrue(functionAware.isEmpty());
		clientConfig = getConfiguration("AMQPRPCService");
		serverConfig = getConfiguration("AMQPubOnSubService");

		String publishTopic = "test_pubonsub";
		String publishContent = "this is an AMQP test";


		// has to be a new configuration
		Dictionary<String, Object> cp = clientConfig.getProperties();
		assertNull(cp);
		Dictionary<String, Object> sp = serverConfig.getProperties();
		assertNull(sp);

		// add client service properties
		cp = new Hashtable<>();
		//		p.put(MessagingConstants.PROP_PUBLISH_TOPICS, publishTopic);
		cp.put(MessagingConstants.PROP_BROKER, brokerUrl);

		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(1);
		// holder for the result
		AtomicReference<String> result = new AtomicReference<>();

		// starting adapter with the given properties
		clientConfig.update(cp);

		// check for service
		MessagingRPCService rpcClient = mrpcsAware.waitForService(2000l);
		assertNotNull(rpcClient);

		ServiceRegistration<Function> functionReg = context.registerService(Function.class, new POSFunction(), null);
		assertNotNull(functionAware.waitForService(2000l));

		// add server service properties
		sp = new Hashtable<>();
		//		p.put(MessagingConstants.PROP_PUBLISH_TOPICS, publishTopic);
		sp.put(MessagingConstants.PROP_BROKER, brokerUrl);
		sp.put(MessagingConstants.PROP_RPC_QUEUE, publishTopic);
		sp.put("pushstream.parallelism", 5);

		serverConfig.update(sp);

		MessagingRPCPubOnSub rpcServer = mpossAware.waitForService(2000l);
		assertNotNull(rpcServer);

		Promise<Message> subscribe = rpcClient.publishRPC(publishTopic, ByteBuffer.wrap(publishContent.getBytes()));
		subscribe.thenAccept((m)->{
			String r = new String(m.payload().array());
			result.set(r);
			resultLatch.countDown();
		});

		// wait and compare the received message
		resultLatch.await(15, TimeUnit.SECONDS);
		assertEquals("Response: " + publishContent, result.get());
		functionReg.unregister();
		serverConfig.delete();
		clientConfig.delete();
		Thread.sleep(500l);
	}

	/**
	 * Tests publishing a message
	 * @throws Exception
	 * WORKS STANDALONE BUT NOT TOGETHER WITH PREVIOUS TEST?!
	 */
	@SuppressWarnings("rawtypes")
	//	@Test
	public void testRPCPubOnSubMessageEnv(@InjectService(cardinality = 0) ServiceAware<MessagingRPCService> mrpcsAware,
			@InjectService(cardinality = 0) ServiceAware<MessagingRPCPubOnSub> mpossAware,
			@InjectService(cardinality = 0) ServiceAware<Function> functionAware) throws Exception {
		assertTrue(mpossAware.isEmpty());
		assertTrue(mrpcsAware.isEmpty());
		assertTrue(functionAware.isEmpty());
		clientConfig = getConfiguration("AMQPRPCService");
		serverConfig = getConfiguration("AMQPubOnSubService");

		String publishTopic = "test_pubonsub";
		String publishContent = "this is an AMQP test";

		// has to be a new configuration
		Dictionary<String, Object> cp = clientConfig.getProperties();
		assertNull(cp);
		Dictionary<String, Object> sp = serverConfig.getProperties();
		assertNull(sp);

		// add client service properties
		cp = new Hashtable<>();
		//		p.put(MessagingConstants.PROP_PUBLISH_TOPICS, publishTopic);
		System.setProperty("AMQP_USER", "demo");
		System.setProperty("AMQP_PWD", "1234");
		cp.put("username.env", "AMQP_USER");
		cp.put("password.env", "AMQP_PWD");
		cp.put("host", amqpHost);
		cp.put("port", 5672);
		cp.put("virtualHost", "test");

		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(1);
		// holder for the result
		AtomicReference<String> result = new AtomicReference<>();

		// starting adapter with the given properties
		clientConfig.update(cp);

		// check for service
		MessagingRPCService rpcClient = mrpcsAware.waitForService(2000l);
		assertNotNull(rpcClient);

		ServiceRegistration<Function> functionReg = context.registerService(Function.class, new POSFunction(), null);
		assertNotNull(functionAware.waitForService(2000l));

		// add server service properties
		sp = new Hashtable<>();
		//		p.put(MessagingConstants.PROP_PUBLISH_TOPICS, publishTopic);
		sp.put("username.env", "AMQP_USER");
		sp.put("password.env", "AMQP_PWD");
		sp.put("host", amqpHost);
		sp.put("port", 5672);
		sp.put("virtualHost", "test");
		sp.put(MessagingConstants.PROP_RPC_QUEUE, publishTopic);
		sp.put("pushstream.parallelism", 5);

		serverConfig.update(sp);

		MessagingRPCPubOnSub rpcServer = mpossAware.waitForService(2000l);
		assertNotNull(rpcServer);

		Promise<Message> subscribe = rpcClient.publishRPC(publishTopic, ByteBuffer.wrap(publishContent.getBytes()));
		subscribe.thenAccept((m)->{
			String r = new String(m.payload().array());
			result.set(r);
			resultLatch.countDown();
		});

		// wait and compare the received message
		resultLatch.await(15, TimeUnit.SECONDS);
		assertEquals("Response: " + publishContent, result.get());
		functionReg.unregister();
		serverConfig.delete();
		clientConfig.delete();
		Thread.sleep(500l);
	}

	/**
	 * Creates a configuration with the configuration admin
	 * @param context the bundle context
	 * @param configId the configuration id
	 * @param createLatch the create latch for waiting
	 * @return the configuration
	 * @throws Exception
	 */
	private Configuration getConfiguration(String configId) throws Exception {
		Configuration clientConfig = configAdmin.getConfiguration(configId, "?");
		assertNotNull(clientConfig);
		return clientConfig;
	}

}
