package org.gecko.adapter.mqtt.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptionsBuilder;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttPersistenceException;
import org.gecko.osgi.messaging.Message;
import org.gecko.osgi.messaging.MessagingConstants;
import org.gecko.osgi.messaging.MessagingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.tracker.ServiceTracker;

@ExtendWith(MockitoExtension.class)
public class MqttComponentSubscribeTest {

	private String brokerUrl = "tcp://devel.data-in-motion.biz:1883";
	private MqttClient checkClient;
	private Configuration clientConfig = null;
	private final BundleContext context = FrameworkUtil.getBundle(MqttComponentSubscribeTest.class).getBundleContext();
	
	@BeforeEach
	public void setup() throws MqttException {
		checkClient = new MqttClient(brokerUrl, "test");
		MqttConnectionOptionsBuilder ob = new MqttConnectionOptionsBuilder();
		ob.username("demo");
		ob.password("1234".getBytes());
		checkClient.connect(ob.build());
	}
	
	@AfterEach
	public void teardown() throws MqttException, IOException {
		checkClient.disconnect();
		checkClient.close();
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
	public void testSubscribeMessage_NoMessage() throws Exception {
//		BundleContext context = createBundleContext();
		final CountDownLatch createLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "MQTTService", createLatch);
		
		String publishTopic = "test.junit";
//		String subscribeTopic = publishTopic;
		
		// has to be a new configuration
		Dictionary<String, Object> p = clientConfig.getProperties();
		assertNull(p);
		// add service properties
		p = new Hashtable<>();
//		p.put(MessagingConstants.PROP_SUBSCRIBE_TOPICS, subscribeTopic);
		p.put(MessagingConstants.PROP_BROKER, brokerUrl);
		p.put(MessagingConstants.PROP_USERNAME, "demo");
		p.put(MessagingConstants.PROP_PASSWORD, "1234");
		
		// track the service
		TestServiceCustomizer<MessagingService, MessagingService> customizer = new TestServiceCustomizer<MessagingService, MessagingService>(context, createLatch);
		ServiceTracker<MessagingService, MessagingService> tracker = new ServiceTracker<MessagingService, MessagingService>(context, MessagingService.class, customizer);
		tracker.open(true);
		
		// starting adapter with the given properties
		clientConfig.update(p);
		
		createLatch.await(5, TimeUnit.SECONDS);
		
		// check for service
		assertEquals(1, customizer.getAddCount());
		MessagingService messagingService = tracker.waitForService(15000l);
		assertNotNull(messagingService);
		
		//send message and wait for the result
		PushStream<Message> subscribeStream = messagingService.subscribe(publishTopic);
		assertNotNull(subscribeStream);
		subscribeStream.close();
		
	}
	
	/**
	 * Tests publishing a message
	 * @throws Exception
	 */
	@Test
	public void testSubscribeOftenMessage_NoMessage() throws Exception {
//		BundleContext context = createBundleContext();
		final CountDownLatch createLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "MQTTService", createLatch);
		
		String publishTopic = "test.junit";
//		String subscribeTopic = publishTopic;
		
		// has to be a new configuration
		Dictionary<String, Object> p = clientConfig.getProperties();
		assertNull(p);
		// add service properties
		p = new Hashtable<>();
//		p.put(MessagingConstants.PROP_SUBSCRIBE_TOPICS, subscribeTopic);
		p.put(MessagingConstants.PROP_BROKER, brokerUrl);
		p.put(MessagingConstants.PROP_USERNAME, "demo");
		p.put(MessagingConstants.PROP_PASSWORD, "1234");
		
		// track the service
		TestServiceCustomizer<MessagingService, MessagingService> customizer = new TestServiceCustomizer<MessagingService, MessagingService>(context, createLatch);
		ServiceTracker<MessagingService, MessagingService> tracker = new ServiceTracker<MessagingService, MessagingService>(context, MessagingService.class, customizer);
		tracker.open(true);
		
		// starting adapter with the given properties
		clientConfig.update(p);
		
		createLatch.await(5, TimeUnit.SECONDS);
		
		// check for service
		assertEquals(1, customizer.getAddCount());
		MessagingService messagingService = tracker.waitForService(15000l);
		assertNotNull(messagingService);
		
		//send message and wait for the result
		PushStream<Message> subscribeStream01 = messagingService.subscribe(publishTopic);
		assertNotNull(subscribeStream01);
		subscribeStream01.close();
		PushStream<Message> subscribeStream02 = messagingService.subscribe(publishTopic);
		assertNotNull(subscribeStream02);
		subscribeStream02.close();
		assertFalse(subscribeStream01.equals(subscribeStream02));
		
	}
	
	/**
	 * Tests publishing a message
	 * @throws Exception
	 */
	@Test
	public void testSubscribeMessage_Message() throws Exception {
//		BundleContext context = createBundleContext();
		final CountDownLatch createLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "MQTTService", createLatch);
		
		String publishTopic = "test-junit";
//		String subscribeTopic = publishTopic;
		String publishContent = "this is a test";
		
		// has to be a new configuration
		Dictionary<String, Object> p = clientConfig.getProperties();
		assertNull(p);
		// add service properties
		p = new Hashtable<>();
//		p.put(MessagingConstants.PROP_SUBSCRIBE_TOPICS, subscribeTopic);
		p.put(MessagingConstants.PROP_BROKER, brokerUrl);
		p.put(MessagingConstants.PROP_USERNAME, "demo");
		p.put(MessagingConstants.PROP_PASSWORD, "1234");
		
		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(1);
		// holder for the result
		AtomicReference<String> result = new AtomicReference<>();
		
		// track the service
		TestServiceCustomizer<MessagingService, MessagingService> customizer = new TestServiceCustomizer<MessagingService, MessagingService>(context, createLatch);
		ServiceTracker<MessagingService, MessagingService> tracker = new ServiceTracker<MessagingService, MessagingService>(context, MessagingService.class, customizer);
		tracker.open(true);
		
		// starting adapter with the given properties
		clientConfig.update(p);
		
		createLatch.await(5, TimeUnit.SECONDS);
		
		// check for service
		assertEquals(1, customizer.getAddCount());
		MessagingService messagingService = tracker.waitForService(15000l);
		assertNotNull(messagingService);
		
		//send message and wait for the result
		PushStream<Message> subscribeStream = messagingService.subscribe(publishTopic);
		
		subscribeStream.forEach((msg)->{
			byte[] c = msg.payload().array();
			result.set(new String(c));
		});
		
		publish(publishTopic, publishContent);
		// wait and compare the received message
		resultLatch.await(5, TimeUnit.SECONDS);
		assertEquals(publishContent, result.get());
		
	}
	
	/**
	 * Tests publishing a message
	 * @throws Exception
	 */
	@Test
	public void testSubscribeMessage_Wildcard() throws Exception {
//		BundleContext context = createBundleContext();
		final CountDownLatch createLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "MQTTService", createLatch);
		
		String publishTopic = "test/junit";
		String subscribeTopic = "test/#";
		String publishContent = "this is a test";
		
		// has to be a new configuration
		Dictionary<String, Object> p = clientConfig.getProperties();
		assertNull(p);
		// add service properties
		p = new Hashtable<>();
//		p.put(MessagingConstants.PROP_SUBSCRIBE_TOPICS, subscribeTopic);
		p.put(MessagingConstants.PROP_BROKER, brokerUrl);
		p.put(MessagingConstants.PROP_USERNAME, "demo");
		p.put(MessagingConstants.PROP_PASSWORD, "1234");
		
		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(1);
		// holder for the result
		AtomicReference<String> result = new AtomicReference<>();
		
		// track the service
		TestServiceCustomizer<MessagingService, MessagingService> customizer = new TestServiceCustomizer<MessagingService, MessagingService>(context, createLatch);
		ServiceTracker<MessagingService, MessagingService> tracker = new ServiceTracker<MessagingService, MessagingService>(context, MessagingService.class, customizer);
		tracker.open(true);
		
		// starting adapter with the given properties
		clientConfig.update(p);
		
		createLatch.await(5, TimeUnit.SECONDS);
		
		// check for service
		assertEquals(1, customizer.getAddCount());
		MessagingService messagingService = tracker.waitForService(15000l);
		assertNotNull(messagingService);
		
		//send message and wait for the result
		PushStream<Message> subscribeStream = messagingService.subscribe(subscribeTopic);
		
		subscribeStream.forEach((msg)->{
			byte[] c = msg.payload().array();
			result.set(new String(c));
		});
		
		publish(publishTopic, publishContent);
		// wait and compare the received message
		resultLatch.await(5, TimeUnit.SECONDS);
		assertEquals(publishContent, result.get());
		
	}
	
	/**
	 * Tests publishing a message
	 * @throws Exception
	 */
	@Test
	public void testSubscribeOftenMessage_Message() throws Exception {
//		BundleContext context = createBundleContext();
		final CountDownLatch createLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "MQTTService", createLatch);
		
		String publishTopic = "test-junit";
//		String subscribeTopic = publishTopic;
		String publishContent = "this is a test";
		
		// has to be a new configuration
		Dictionary<String, Object> p = clientConfig.getProperties();
		assertNull(p);
		// add service properties
		p = new Hashtable<>();
//		p.put(MessagingConstants.PROP_SUBSCRIBE_TOPICS, subscribeTopic);
		p.put(MessagingConstants.PROP_BROKER, brokerUrl);
		p.put(MessagingConstants.PROP_USERNAME, "demo");
		p.put(MessagingConstants.PROP_PASSWORD, "1234");
		
		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(1);
		// holder for the result
		AtomicReference<String> result01 = new AtomicReference<>();
		AtomicReference<String> result02 = new AtomicReference<>();
		
		// track the service
		TestServiceCustomizer<MessagingService, MessagingService> customizer = new TestServiceCustomizer<MessagingService, MessagingService>(context, createLatch);
		ServiceTracker<MessagingService, MessagingService> tracker = new ServiceTracker<MessagingService, MessagingService>(context, MessagingService.class, customizer);
		tracker.open(true);
		
		// starting adapter with the given properties
		clientConfig.update(p);
		
		createLatch.await(5, TimeUnit.SECONDS);
		
		// check for service
		assertEquals(1, customizer.getAddCount());
		MessagingService mqttService = tracker.waitForService(15000l);
		assertNotNull(mqttService);
		
		//send message and wait for the result
		PushStream<Message> subscribeStream01 = mqttService.subscribe(publishTopic);
		PushStream<Message> subscribeStream02 = mqttService.subscribe(publishTopic);
		
		subscribeStream01.forEach((msg)->{
			byte[] c = msg.payload().array();
			result01.set(new String(c));
		});
		subscribeStream02.forEach((msg)->{
			byte[] c = msg.payload().array();
			result02.set(new String(c));
		});
		
		publish(publishTopic, publishContent);
		// wait and compare the received message
		resultLatch.await(5, TimeUnit.SECONDS);
		assertEquals(publishContent, result01.get());
		assertEquals(publishContent, result02.get());
		assertEquals(result01.get(), result02.get());
		
	}
	
//	/**
//	 * Tests publishing a message with an invalid topic
//	 * @throws Exception
//	 */
//	@Test(expected=IllegalArgumentException.class)
//	public void testSubscribeMessage_WrongTopic() throws Exception {
////		BundleContext context = createBundleContext();
//		final CountDownLatch createLatch = new CountDownLatch(1);
//		clientConfig = getConfiguration(context, "MQTTService", createLatch);
//		
////		String publishTopic = "test.junit";
////		String subscribeTopic = publishTopic;
//		
//		// has to be a new configuration
//		Dictionary<String, Object> p = clientConfig.getProperties();
//		assertNull(p);
//		// add service properties
//		p = new Hashtable<>();
////		p.put(MessagingConstants.PROP_SUBSCRIBE_TOPICS, subscribeTopic);
//		p.put(MessagingConstants.PROP_BROKER, brokerUrl);
//		
//		// track the service
//		TestServiceCustomizer<MessagingService, MessagingService> customizer = new TestServiceCustomizer<MessagingService, MessagingService>(context, createLatch);
//		ServiceTracker<MessagingService, MessagingService> tracker = new ServiceTracker<MessagingService, MessagingService>(context, MessagingService.class, customizer);
//		tracker.open(true);
//		
//		// starting adapter with the given properties
//		clientConfig.update(p);
//		
//		createLatch.await(5, TimeUnit.SECONDS);
//		
//		// check for service
//		assertEquals(1, customizer.getAddCount());
//		MessagingService messagingService = tracker.waitForService(15000l);
//		assertNotNull(messagingService);
//		
//		//send message and wait for the result
//		messagingService.subscribe("my.topic");
//	}
	
	/**
	 * Publishes some content to a given topic
	 * @param topic
	 * @param messageString
	 * @throws MqttPersistenceException
	 * @throws MqttException
	 */
	private void publish(String topic, String messageString) throws MqttPersistenceException, MqttException {
		assertNotNull(topic);
		assertNotNull(messageString);
		MqttMessage message = new MqttMessage();
		message.setPayload(messageString.getBytes());
		checkClient.publish(topic, message);
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
	 * Creates a bundle context
	 * @return the bundle context
	 * @throws BundleException
	 */
//	private BundleContext createBundleContext() throws BundleException {
//		Bundle bundle = FrameworkUtil.getBundle(MQTTAdapter.class);
//		assertEquals("org.eclipselabs.osgi.mqtt", bundle.getSymbolicName());
//		bundle.start();
//		assertEquals(Bundle.ACTIVE, bundle.getState());
//		
//		// get bundle context
//		BundleContext context = bundle.getBundleContext();
//		assertNotNull(context);
//		return context;
//	}
	
}
