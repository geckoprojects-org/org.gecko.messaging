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

import org.gecko.adapter.amqp.client.AMQPClient;
import org.gecko.osgi.messaging.Message;
import org.gecko.osgi.messaging.MessagingConstants;
import org.gecko.osgi.messaging.MessagingReplyToService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.junit5.context.BundleContextExtension;
import org.osgi.util.promise.Promise;
import org.osgi.util.tracker.ServiceTracker;

@ExtendWith(MockitoExtension.class)
@ExtendWith(BundleContextExtension.class)
public class AMQPComponentReplyToServiceTest {

	private String amqpHost = System.getProperty("amqp.host", "localhost");
	private String brokerUrl = "amqp://demo:1234@" + amqpHost + ":5672/test";
	private AMQPClient checkClient;
	private Configuration clientConfig = null;
	@InjectBundleContext
	BundleContext context;
	
	@BeforeEach
	public void setup() throws Exception {
		checkClient = new AMQPClient();
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
	@org.junit.jupiter.api.Test
	public void testSingleReplyToMessage() throws Exception {
		final CountDownLatch createLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "AMQPReplyToService", createLatch);

		String publishTopic = "test_rpc";
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


		// starting adapter with the given properties
		clientConfig.update(p);

		createLatch.await(2, TimeUnit.SECONDS);

		// check for service
		MessagingReplyToService messagingService = getService(MessagingReplyToService.class, 30000l);
		assertNotNull(messagingService);
		checkClient.registerRPCEcho(publishTopic);
		Promise<Message> subscribe = messagingService.publishSingle(publishTopic, ByteBuffer.wrap(publishContent.getBytes()));
		subscribe.thenAccept((m)->{
			String r = new String(m.payload().array());
			result.set(r);
			resultLatch.countDown();
		});

		// wait and compare the received message
		resultLatch.await(15, TimeUnit.SECONDS);
		assertEquals(publishContent, result.get());
	}
	
	/**
	 * Tests publishing a message
	 * @throws Exception
	 */
	@Test
	public void testSingleReplyToMessageEnv() throws Exception {
		final CountDownLatch createLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "AMQPReplyToService", createLatch);
		
		String publishTopic = "test_rpc";
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
		
		
		// starting adapter with the given properties
		clientConfig.update(p);
		
		createLatch.await(2, TimeUnit.SECONDS);
		
		// check for service
		MessagingReplyToService messagingService = getService(MessagingReplyToService.class, 30000l);
		assertNotNull(messagingService);
		checkClient.registerRPCEcho(publishTopic);
		Promise<Message> subscribe = messagingService.publishSingle(publishTopic, ByteBuffer.wrap(publishContent.getBytes()));
		subscribe.thenAccept((m)->{
			String r = new String(m.payload().array());
			result.set(r);
			resultLatch.countDown();
		});
		
		// wait and compare the received message
		resultLatch.await(15, TimeUnit.SECONDS);
		assertEquals(publishContent, result.get());
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
