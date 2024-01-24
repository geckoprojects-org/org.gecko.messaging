package org.gecko.adapter.amqp.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.util.tracker.ServiceTracker;

/**
 * test for 
 * @author mark
 * @since 27.11.2018
 */
@RunWith(MockitoJUnitRunner.class)
public class AMQPComponentPublishTest {

	private String amqpHost = System.getProperty("amqp.host", "devel.data-in-motion.biz");
	private String brokerUrl = "amqp://demo:1234@" + amqpHost + ":5672/test";
	public static final String PUBLISH_TOPIC = "test_q";
	public static final String PUBLISH_FAN_TOPIC = "test_pfan";
	public static final String PUBLISH_DIR_TOPIC = "test_pdir";
	private AMQPClient checkClient;
	private Configuration clientConfig = null;
	private final BundleContext context = FrameworkUtil.getBundle(AMQPComponentPublishTest.class).getBundleContext();

	@Before
	public void setup() throws Exception {
		checkClient = new AMQPClient(amqpHost);
	}

	@After
	public void teardown() throws Exception {
		checkClient.purgeChannel(PUBLISH_TOPIC);
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
	public void testPublishMessage() throws Exception {
//		BundleContext context = createBundleContext();
		final CountDownLatch createLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "AMQPService", createLatch);

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

		connectClient(PUBLISH_TOPIC, resultLatch, result);

		// starting adapter with the given properties
		clientConfig.update(p);
		

		createLatch.await(10, TimeUnit.SECONDS);

		// check for service
		MessagingService messagingService = getService(MessagingService.class, 30000l);
		assertNotNull(messagingService);

		//send message and wait for the result
		messagingService.publish(PUBLISH_TOPIC, ByteBuffer.wrap(publishContent.getBytes()));

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
	public void testPublishFanoutMessage() throws Exception {
//		BundleContext context = createBundleContext();
		final CountDownLatch createLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "AMQPService", createLatch);
		
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
		
		
		createLatch.await(3, TimeUnit.SECONDS);
		
		// check for service
		MessagingService messagingService = getService(MessagingService.class, 10000l);
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
	public void testPublishDirectMulticastMessage() throws Exception {
//		BundleContext context = createBundleContext();
		final CountDownLatch createLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "AMQPService", createLatch);
		
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
		
		
		createLatch.await(3, TimeUnit.SECONDS);
		
		// check for service
		MessagingService messagingService = getService(MessagingService.class, 10000l);
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
	public void testPublishMessageEnv() throws Exception {
//		BundleContext context = createBundleContext();
		final CountDownLatch createLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "AMQPService", createLatch);
		
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
		
		connectClient(PUBLISH_TOPIC, resultLatch, result);
		
		// starting adapter with the given properties
		clientConfig.update(p);
		
		
		createLatch.await(10, TimeUnit.SECONDS);
		
		// check for service
		MessagingService messagingService = getService(MessagingService.class, 30000l);
		assertNotNull(messagingService);
		
		//send message and wait for the result
		messagingService.publish(PUBLISH_TOPIC, ByteBuffer.wrap(publishContent.getBytes()));
		
		// wait and compare the received message
		resultLatch.await(5, TimeUnit.SECONDS);
		assertEquals(publishContent, result.get());
		
	}
	
	/**
	 * Tests publishing a message
	 * @throws Exception
	 */
	@Test
	public void testPublishMessage_wrongQueue() throws Exception {
		final CountDownLatch createLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "AMQPService", createLatch);
		
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
		
		
		createLatch.await(10, TimeUnit.SECONDS);
		
		// check for service
		MessagingService messagingService = getService(MessagingService.class, 30000l);
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
