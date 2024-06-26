package org.gecko.adapter.mqtt.tests;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptionsBuilder;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.gecko.osgi.messaging.MessagingConstants;
import org.gecko.osgi.messaging.MessagingService;
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
import org.osgi.service.cm.annotations.RequireConfigurationAdmin;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.junit5.context.BundleContextExtension;
import org.osgi.util.tracker.ServiceTracker;

@RequireConfigurationAdmin
@ExtendWith(MockitoExtension.class)
@ExtendWith(BundleContextExtension.class)
public class MqttComponentPublishTest {

	private String brokerUrl = "tcp://devel.data-in-motion.biz:1883";
	private MqttClient checkClient;
	private Configuration clientConfig = null;
	@InjectBundleContext
	BundleContext context;

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
	public void testPublishMessage() throws Exception {
		final CountDownLatch createLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "MQTTService", createLatch);

		String publishTopic = "publish.junit";
		String publishContent = "this is a test";

		// has to be a new configuration
		Dictionary<String, Object> p = clientConfig.getProperties();
		assertNull(p);
		// add service properties
		p = new Hashtable<>();
//		p.put(MessagingConstants.PROP_PUBLISH_TOPICS, publishTopic);
		p.put(MessagingConstants.PROP_BROKER, brokerUrl);
		p.put(MessagingConstants.PROP_USERNAME, "demo");
		p.put(MessagingConstants.PROP_PASSWORD, "1234");

		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(1);
		// holder for the result
		AtomicReference<String> result = new AtomicReference<>();

		connectClient(publishTopic, resultLatch, result);

		// starting adapter with the given properties
		clientConfig.update(p);
		

		createLatch.await(10, TimeUnit.SECONDS);

		// check for service
		MessagingService messagingService = getService(MessagingService.class, 30000l);
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
	public void testPublishMessage_WildCard() throws Exception {
//		BundleContext context = createBundleContext();
		final CountDownLatch createLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "MQTTService", createLatch);

		String publishTopic = "publish.bla";
//		String registeredPublishTopic = "publish.*";
		String publishContent = "this is a test";

		// has to be a new configuration
		Dictionary<String, Object> p = clientConfig.getProperties();
		assertNull(p);
		// add service properties
		p = new Hashtable<>();
//		p.put(MessagingConstants.PROP_PUBLISH_TOPICS, registeredPublishTopic);
		p.put(MessagingConstants.PROP_BROKER, brokerUrl);
		p.put(MessagingConstants.PROP_USERNAME, "demo");
		p.put(MessagingConstants.PROP_PASSWORD, "1234");

		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(1);
		// holder for the result
		AtomicReference<String> result = new AtomicReference<>();

		connectClient(publishTopic, resultLatch, result);

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
	public void testPublishMessageWithUsernameAndPassword() throws Exception {
//		BundleContext context = createBundleContext();
		final CountDownLatch createLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "MQTTService", createLatch);

		String publishTopic = "publish.junit";
		String publishContent = "this is a test";

		checkClient.disconnect();
		checkClient.close();
		
		checkClient = new MqttClient("tcp://devel.data-in-motion.biz:1883", "test");
		MqttConnectionOptionsBuilder ob = new MqttConnectionOptionsBuilder();
		ob.username("demo");
		ob.password("1234".getBytes());
		checkClient.connect(ob.build());
		
		// has to be a new configuration
		Dictionary<String, Object> p = clientConfig.getProperties();
		assertNull(p);
		// add service properties
		p = new Hashtable<>();
//		p.put(MessagingConstants.PROP_PUBLISH_TOPICS, publishTopic);
		p.put(MessagingConstants.PROP_BROKER, "tcp://devel.data-in-motion.biz:1883");
		p.put(MessagingConstants.PROP_USERNAME, "demo");
		p.put(MessagingConstants.PROP_PASSWORD, "1234");

		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(1);
		// holder for the result
		AtomicReference<String> result = new AtomicReference<>();

		connectClient(publishTopic, resultLatch, result);

		// starting adapter with the given properties
		clientConfig.update(p);
		

		createLatch.await(10, TimeUnit.SECONDS);

		// check for service
		MessagingService messagingService = getService(MessagingService.class, 30000l);
		assertNotNull(messagingService);

		//send message and wait for the result
		messagingService.publish(publishTopic, ByteBuffer.wrap(publishContent.getBytes()));

		// wait and compare the received message
		resultLatch.await(5, TimeUnit.SECONDS);
		assertEquals(publishContent, result.get());

	}

//	/**
//	 * Tests publishing a message on an 
//	 * @throws Exception
//	 */
//	@Test(expected=IllegalArgumentException.class)
//	public void testPublishMessage_WrongTopic() throws Exception {
////		BundleContext context = createBundleContext();
//		final CountDownLatch createLatch = new CountDownLatch(1);
//		clientConfig = getConfiguration(context, "MQTTService", createLatch);
//
//		String publishTopic = "publish.junit";
//		String registeredPublishTopic = "publish.junit.other";
//		String subscribeTopic = registeredPublishTopic;
//		String publishContent = "this is a test";
//
//		// has to be a new configuration
//		Dictionary<String, Object> p = clientConfig.getProperties();
//		assertNull(p);
//		// add service properties
//		p = new Hashtable<>();
////		p.put(MessagingConstants.PROP_PUBLISH_TOPICS, registeredPublishTopic);
//		p.put(MessagingConstants.PROP_BROKER, brokerUrl);
//
//		// count down latch to wait for the message
//		CountDownLatch resultLatch = new CountDownLatch(1);
//		// holder for the result
//		AtomicReference<String> result = new AtomicReference<>();
//
//		connectClient(subscribeTopic, resultLatch, result);
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
//		messagingService.publish(publishTopic, ByteBuffer.wrap(publishContent.getBytes()));
//
//		// wait and compare the received message
//		resultLatch.await(5, TimeUnit.SECONDS);
//		assertNull(result.get());
//
//	}

//	/**
//	 * Tests publishing a message
//	 * @throws Exception
//	 */
//	@Test
//	public void testPublishOnSubscribe() throws Exception {
////		BundleContext context = createBundleContext();
//		final CountDownLatch createLatch = new CountDownLatch(1);
//		clientConfig = getConfiguration(context, "MQTTService", createLatch);
//
//		String subscribeTopic = "subscribe.junit,publish.junit";
//		String publishTopic = "publish.junit";
//		String publishOnSubscribeTopic = "subscribe.junit";
//		String publishContent01 = "this is a test";
//		String publishContent02 = "this is a second test";
//
//		// has to be a new configuration
//		Dictionary<String, Object> p = clientConfig.getProperties();
//		assertNull(p);
//		// add service properties
//		p = new Hashtable<>();
//		p.put(MessagingConstants.PROP_PUBLISH_TOPICS, publishTopic);
//		p.put(MessagingConstants.PROP_SUBSCRIBE_TOPICS, subscribeTopic);
//		p.put(MessagingConstants.PROP_PUBLISH_ON_SUBSCRIBE, Boolean.TRUE);
//		p.put(MessagingConstants.PROP_BROKER, brokerUrl);
//
//		// count down latch to wait for the message
//		CountDownLatch resultLatch = new CountDownLatch(1);
//		// holder for the result
//		AtomicReference<String> result = new AtomicReference<>();
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
//		PushStream<Message> subscribeStream = messagingService.subscribe(publishTopic);
//
//		subscribeStream.forEach((msg)->{
//			byte[] c = msg.payload().array();
//			result.set(new String(c));
//		});
//
//		publish(publishTopic, publishContent01);
//		// wait and compare the received message
//		resultLatch.await(5, TimeUnit.SECONDS);
//		assertEquals(publishContent01, result.get());
//
//		/*
//		 * Now check, if we can send on an subscribed topic
//		 */
//		
//		// count down latch to wait for the message
//		resultLatch = new CountDownLatch(1);
//		result.set(null);
//		connectClient(publishOnSubscribeTopic, resultLatch, result);
//
//		//send message and wait for the result
//		messagingService.publish(publishOnSubscribeTopic, ByteBuffer.wrap(publishContent02.getBytes()));
//
//		// wait and compare the received message
//		resultLatch.await(5, TimeUnit.SECONDS);
//		assertEquals(publishContent02, result.get());
//
//	}

//	/**
//	 * Publishes some content to a given topic
//	 * @param topic
//	 * @param messageString
//	 * @throws MqttPersistenceException
//	 * @throws MqttException
//	 */
//	private void publish(String topic, String messageString) throws MqttPersistenceException, MqttException {
//		assertNotNull(topic);
//		assertNotNull(messageString);
//		MqttMessage message = new MqttMessage();
//		message.setPayload(messageString.getBytes());
//		checkClient.publish(topic, message);
//	}

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
	private void connectClient(String topic, CountDownLatch checkLatch, AtomicReference<String> resultContent) throws MqttException {
		checkClient.subscribe(topic,0);
		checkClient.setCallback(new MqttCallback() {

			@Override
			public void messageArrived(String arg0, MqttMessage arg1) throws Exception {
				String v = new String(arg1.getPayload());
				resultContent.set(v);
				checkLatch.countDown();

			}


			@Override
			public void disconnected(MqttDisconnectResponse disconnectResponse) {
				fail("fail was not expected");
			}

			@Override
			public void mqttErrorOccurred(MqttException exception) {
				fail("fail was not expected");
			}

			@Override
			public void deliveryComplete(IMqttToken token) {
				fail("delivery complete was not expected");
			}

			@Override
			public void connectComplete(boolean reconnect, String serverURI) {
				// TODO Auto-generated method stub
				
			}

			@Override
			public void authPacketArrived(int reasonCode, MqttProperties properties) {
				// TODO Auto-generated method stub
				
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
