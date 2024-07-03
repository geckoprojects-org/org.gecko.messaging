package org.gecko.adapter.mqtt.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptionsBuilder;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttPersistenceException;
import org.gecko.moquette.broker.MQTTBroker;
import org.gecko.osgi.messaging.Message;
import org.gecko.osgi.messaging.MessagingConstants;
import org.gecko.osgi.messaging.MessagingService;
import org.gecko.osgi.messaging.annotations.RequireMQTTv5;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.test.common.annotation.InjectService;
import org.osgi.test.common.annotation.Property;
import org.osgi.test.common.annotation.config.WithFactoryConfiguration;
import org.osgi.test.common.service.ServiceAware;
import org.osgi.test.junit5.cm.ConfigurationExtension;
import org.osgi.test.junit5.service.ServiceExtension;
import org.osgi.util.pushstream.PushStream;

@ExtendWith(MockitoExtension.class)
@ExtendWith(ServiceExtension.class)
@ExtendWith(ConfigurationExtension.class)
@RequireMQTTv5
public class MqttComponentSubscribeTest {

	private static final String BROKER_URL = "tcp://localhost:2183";
	private MqttClient checkClient;

	
	@AfterEach
	public void teardown() throws MqttException, IOException {
		if (checkClient != null) {
			if (checkClient.isConnected()) {
				checkClient.disconnect();
			}
			checkClient.close();
		}
	}
	
	/**
	 * Tests publishing a message
	 * @throws Exception
	 */
	@Test
	@WithFactoryConfiguration(factoryPid = "MQTTBroker", location = "?", name = "broker", properties = {
			@Property(key = MQTTBroker.HOST, value = "localhost"), //
			@Property(key = MQTTBroker.PORT, value = "2183") })
	@WithFactoryConfiguration(factoryPid = "MQTTService", location = "?", name = "read", properties = {
			@Property(key = MessagingConstants.PROP_BROKER, value = BROKER_URL) })
	public void testSubscribeMessage_NoMessage(@InjectService(cardinality = 0) ServiceAware<MQTTBroker> bAware,
			@InjectService(cardinality = 0) ServiceAware<MessagingService> msAware) throws Exception {

		String publishTopic = "test.junit";
//		String subscribeTopic = publishTopic;
		MQTTBroker broker = bAware.getService();
		assertNotNull(broker);

		MessagingService messagingService = msAware.getService();
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
	@WithFactoryConfiguration(factoryPid = "MQTTBroker", location = "?", name = "broker", properties = {
			@Property(key = MQTTBroker.HOST, value = "localhost"), //
			@Property(key = MQTTBroker.PORT, value = "2183") })
	@WithFactoryConfiguration(factoryPid = "MQTTService", location = "?", name = "read", properties = {
			@Property(key = MessagingConstants.PROP_BROKER, value = BROKER_URL) })
	public void testSubscribeOftenMessage_NoMessage(@InjectService(cardinality = 0) ServiceAware<MQTTBroker> bAware,
			@InjectService(cardinality = 0) ServiceAware<MessagingService> msAware) throws Exception {

		String publishTopic = "test.junit";
//		String subscribeTopic = publishTopic;
		MQTTBroker broker = bAware.getService();
		assertNotNull(broker);

		MessagingService messagingService = msAware.getService();
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
	@WithFactoryConfiguration(factoryPid = "MQTTBroker", location = "?", name = "broker", properties = {
			@Property(key = MQTTBroker.HOST, value = "localhost"), //
			@Property(key = MQTTBroker.PORT, value = "2183") })
	@WithFactoryConfiguration(factoryPid = "MQTTService", location = "?", name = "read", properties = {
			@Property(key = MessagingConstants.PROP_BROKER, value = BROKER_URL) })
	public void testSubscribeMessage_Message(@InjectService(cardinality = 0) ServiceAware<MQTTBroker> bAware,
			@InjectService(cardinality = 0) ServiceAware<MessagingService> msAware) throws Exception {

		String publishTopic = "test.junit";
		String publishContent = "this is a test";

		MQTTBroker broker = bAware.getService();
		assertNotNull(broker);

		MessagingService messagingService = msAware.getService();
		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(1);
		// holder for the result
		AtomicReference<String> result = new AtomicReference<>();
		assertNotNull(messagingService);
		
		//send message and wait for the result
		PushStream<Message> subscribeStream = messagingService.subscribe(publishTopic);
		
		subscribeStream.forEach((msg)->{
			byte[] c = msg.payload().array();
			result.set(new String(c));
			resultLatch.countDown();
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
	@WithFactoryConfiguration(factoryPid = "MQTTBroker", location = "?", name = "broker", properties = {
			@Property(key = MQTTBroker.HOST, value = "localhost"), //
			@Property(key = MQTTBroker.PORT, value = "2183") })
	@WithFactoryConfiguration(factoryPid = "MQTTService", location = "?", name = "read", properties = {
			@Property(key = MessagingConstants.PROP_BROKER, value = BROKER_URL) })
	public void testSubscribeMessage_Wildcard(@InjectService(cardinality = 0) ServiceAware<MQTTBroker> bAware,
			@InjectService(cardinality = 0) ServiceAware<MessagingService> msAware) throws Exception {

		String publishTopic = "test/junit";
		String subscribeTopic = "test/#";
		String publishContent = "this is a test";

		MQTTBroker broker = bAware.getService();
		assertNotNull(broker);

		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(1);
		// holder for the result
		AtomicReference<String> result = new AtomicReference<>();
		
		MessagingService messagingService = msAware.getService();
		assertNotNull(messagingService);
		
		//send message and wait for the result
		PushStream<Message> subscribeStream = messagingService.subscribe(subscribeTopic);
		
		subscribeStream.forEach((msg)->{
			byte[] c = msg.payload().array();
			result.set(new String(c));
			resultLatch.countDown();
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
	@WithFactoryConfiguration(factoryPid = "MQTTBroker", location = "?", name = "broker", properties = {
			@Property(key = MQTTBroker.HOST, value = "localhost"), //
			@Property(key = MQTTBroker.PORT, value = "2183") })
	@WithFactoryConfiguration(factoryPid = "MQTTService", location = "?", name = "read", properties = {
			@Property(key = MessagingConstants.PROP_BROKER, value = BROKER_URL) })
	public void testSubscribeOftenMessage_Message(@InjectService(cardinality = 0) ServiceAware<MQTTBroker> bAware,
			@InjectService(cardinality = 0) ServiceAware<MessagingService> msAware) throws Exception {

		String publishTopic = "test-junit";
//		String subscribeTopic = publishTopic;
		String publishContent = "this is a test";

		MQTTBroker broker = bAware.getService();
		assertNotNull(broker);

		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(2);
		// holder for the result
		AtomicReference<String> result01 = new AtomicReference<>();
		AtomicReference<String> result02 = new AtomicReference<>();
		
		MessagingService mqttService = msAware.getService();
		assertNotNull(mqttService);
		
		//send message and wait for the result
		PushStream<Message> subscribeStream01 = mqttService.subscribe(publishTopic);
		PushStream<Message> subscribeStream02 = mqttService.subscribe(publishTopic);
		
		subscribeStream01.forEach((msg)->{
			byte[] c = msg.payload().array();
			result01.set(new String(c));
			resultLatch.countDown();
		});
		subscribeStream02.forEach((msg)->{
			byte[] c = msg.payload().array();
			result02.set(new String(c));
			resultLatch.countDown();
		});
		publish(publishTopic, publishContent);
		// wait and compare the received message
		resultLatch.await(3, TimeUnit.SECONDS);
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
		checkClient = new MqttClient(BROKER_URL, "test");
		MqttConnectionOptionsBuilder ob = new MqttConnectionOptionsBuilder();
		ob.username("demo");
		ob.password("1234".getBytes());
		checkClient.connect(ob.build());
		checkClient.publish(topic, message);
	}
		
}
