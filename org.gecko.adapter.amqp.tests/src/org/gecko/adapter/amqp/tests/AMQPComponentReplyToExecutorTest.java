package org.gecko.adapter.amqp.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.gecko.osgi.messaging.Message;
import org.gecko.osgi.messaging.MessagingConstants;
import org.gecko.osgi.messaging.MessagingRPCPubOnSub;
import org.gecko.osgi.messaging.MessagingReplyToService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.junit5.context.BundleContextExtension;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.tracker.ServiceTracker;

@ExtendWith(MockitoExtension.class)
@ExtendWith(BundleContextExtension.class)
public class AMQPComponentReplyToExecutorTest {

	private String amqpHost = System.getProperty("amqp.host", "localhost");
	private String brokerUrl = "amqp://demo:1234@" + amqpHost + ":5672/test";
	private Configuration clientConfig = null;
	private Configuration serverConfig = null;
	@InjectBundleContext
	BundleContext context;
	
	@BeforeEach
	public void setup() throws Exception {
	}

	@AfterEach
	public void teardown() throws Exception {
		if (serverConfig != null) {
			serverConfig.delete();
			serverConfig = null;
		}
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
	public void testRPCPubOnSubMessage() throws Exception {
		final CountDownLatch createClientLatch = new CountDownLatch(1);
		final CountDownLatch createServerLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "AMQPReplyToService", createClientLatch);
		serverConfig = getConfiguration(context, "AMQPReplyToExecutor", createServerLatch);

		String publishTopic = "test_pubonsubmany";
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
		CountDownLatch resultLatch = new CountDownLatch(5);
		// holder for the result
		List<String> result = new LinkedList<String>();

		// starting adapter with the given properties
		clientConfig.update(cp);
		createClientLatch.await(2, TimeUnit.SECONDS);

		// check for service
		MessagingReplyToService rpcClient = getService(MessagingReplyToService.class, 3000l);
		assertNotNull(rpcClient);

		@SuppressWarnings("rawtypes")
		ServiceRegistration<Function> functionReg = context.registerService(Function.class, new POSManyFunction(), null);

		// add server service properties
		sp = new Hashtable<>();
		//		p.put(MessagingConstants.PROP_PUBLISH_TOPICS, publishTopic);
		sp.put(MessagingConstants.PROP_BROKER, brokerUrl);
		sp.put(MessagingConstants.PROP_RPC_QUEUE, publishTopic);
		sp.put("pushstream.parallelism", 5);

		serverConfig.update(sp);
		createServerLatch.await(2, TimeUnit.SECONDS);

		MessagingRPCPubOnSub rpcServer = getService(MessagingRPCPubOnSub.class, 30000l);
		assertNotNull(rpcServer);

		PushStream<Message> subscribe = rpcClient.publishMany(publishTopic, ByteBuffer.wrap(publishContent.getBytes()));
		subscribe.forEach((m)-> {
			String r = new String(m.payload().array());
			result.add(r);
			resultLatch.countDown();
		}).onFailure((t)->fail("Unexpected error in consuming push events"));

		// wait and compare the received message
		resultLatch.await(15, TimeUnit.SECONDS);
		assertEquals(5, result.size());
		functionReg.unregister();
	}
	
	/**
	 * Tests publishing a message
	 * @throws Exception
	 */
	@Test
	public void testRPCPubOnSubMessageEnv() throws Exception {
		final CountDownLatch createClientLatch = new CountDownLatch(1);
		final CountDownLatch createServerLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "AMQPReplyToService", createClientLatch);
		serverConfig = getConfiguration(context, "AMQPReplyToExecutor", createServerLatch);
		
		String publishTopic = "test_pubonsubmany";
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
		CountDownLatch resultLatch = new CountDownLatch(5);
		// holder for the result
		List<String> result = new LinkedList<String>();
		
		// starting adapter with the given properties
		clientConfig.update(cp);
		createClientLatch.await(2, TimeUnit.SECONDS);
		
		// check for service
		MessagingReplyToService rpcClient = getService(MessagingReplyToService.class, 3000l);
		assertNotNull(rpcClient);
		
		@SuppressWarnings("rawtypes")
		ServiceRegistration<Function> functionReg = context.registerService(Function.class, new POSManyFunction(), null);
		
		// add server service properties
		sp = new Hashtable<>();
		//		p.put(MessagingConstants.PROP_PUBLISH_TOPICS, publishTopic);
		sp.put(MessagingConstants.PROP_BROKER, brokerUrl);
		sp.put(MessagingConstants.PROP_RPC_QUEUE, publishTopic);
		sp.put("pushstream.parallelism", 5);
		
		serverConfig.update(sp);
		createServerLatch.await(2, TimeUnit.SECONDS);
		
		MessagingRPCPubOnSub rpcServer = getService(MessagingRPCPubOnSub.class, 30000l);
		assertNotNull(rpcServer);
		
		PushStream<Message> subscribe = rpcClient.publishMany(publishTopic, ByteBuffer.wrap(publishContent.getBytes()));
		subscribe.forEach((m)-> {
			String r = new String(m.payload().array());
			result.add(r);
			resultLatch.countDown();
		}).onFailure((t)->fail("Unexpected error in consuming push events"));
		
		// wait and compare the received message
		resultLatch.await(15, TimeUnit.SECONDS);
		assertEquals(5, result.size());
		functionReg.unregister();
	}

	/**
	 * Creates a configuration with the configuration admin
	 * @param context the bundle context
	 * @param configId the configuration id
	 * @param createLatch the create latch for waiting
	 * @return the configuration
	 * @throws Exception
	 */
	private Configuration getConfiguration(BundleContext context, String configId, CountDownLatch createLatch) throws Exception {

		// service lookup for configuration admin service
		ServiceReference<?>[] allServiceReferences = context.getAllServiceReferences(ConfigurationAdmin.class.getName(), null);
		assertNotNull(allServiceReferences);
		assertEquals(1, allServiceReferences.length);
		ServiceReference<?> cmRef = allServiceReferences[0];
		Object service = context.getService(cmRef);
		assertNotNull(service);
		assertTrue(service instanceof ConfigurationAdmin);

		// create MQTT client configuration
		ConfigurationAdmin cm = (ConfigurationAdmin) service;
		Configuration clientConfig = cm.getConfiguration(configId, "?");
		assertNotNull(clientConfig);

		return clientConfig;
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
