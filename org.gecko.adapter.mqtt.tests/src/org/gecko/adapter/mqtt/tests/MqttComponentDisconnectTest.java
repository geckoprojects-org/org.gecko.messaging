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
import java.util.UUID;
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
public class MqttComponentDisconnectTest {

	private String brokerUrl = "tcp://localhost:1883";
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
	 * 
	 * @throws Exception
	 */
	@Test
	public void test() throws Exception {
		final CountDownLatch createLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "MQTTService", createLatch);

		String publishTopic = "publish.junit." + UUID.randomUUID();
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

		createLatch.await(3, TimeUnit.SECONDS);

		// check for service
		MessagingService messagingService = getService(MessagingService.class, 30000l);

		assertNotNull(messagingService);
		boolean isError = false;
		// send message and wait for the result
		while (true) {
			try {
				messagingService.publish(publishTopic, ByteBuffer.wrap(publishContent.getBytes()));
				// wait and compare the received message
				resultLatch.await(1, TimeUnit.SECONDS);
				assertEquals(publishContent, result.get());
				if (isError) {
					System.out.println("message send.");
					isError = false;
				}
			} catch (Exception e) {
				if (!isError) {
					System.out.println("Ex: " + e.getLocalizedMessage());
					isError = true;
				}
			}
//				Thread.sleep(1000);
		}

//			createLatch.await(10, TimeUnit.SECONDS);
	}

	/**
	 * Creates a configuration with the configuration admin
	 * 
	 * @param context     the bundle context
	 * @param configId    the configuration id
	 * @param createLatch the create latch for waiting
	 * @return the configuration
	 * @throws Exception
	 */
	private Configuration getConfiguration(BundleContext context, String configId, CountDownLatch createLatch)
			throws Exception {

		// service lookup for configuration admin service
		ServiceReference<?>[] allServiceReferences = context.getAllServiceReferences(ConfigurationAdmin.class.getName(),
				null);
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
	 * 
	 * @param topic         the topic to connect
	 * @param checkLatch    the check latch to block
	 * @param resultContent the {@link AtomicReference} for the content
	 * @throws MqttException
	 */
	private void connectClient(String topic, CountDownLatch checkLatch, AtomicReference<String> resultContent)
			throws MqttException {
		checkClient.subscribe(topic, 0);
		checkClient.setCallback(new MqttCallback() {

			@Override
			public void messageArrived(String arg0, MqttMessage msg) throws Exception {
				String v = new String(msg.getPayload());
				resultContent.set(v);
				checkLatch.countDown();

			}

			@Override
			public void disconnected(MqttDisconnectResponse disconnectResponse) {
				System.out.println("fail was not expected" + disconnectResponse);
			}

			@Override
			public void mqttErrorOccurred(MqttException exception) {
				System.out.println("fail was not expected");
			}

			@Override
			public void deliveryComplete(IMqttToken token) {
				System.out.println("delivery complete was not expected");
			}

			@Override
			public void connectComplete(boolean reconnect, String serverURI) {
				System.out.println("delivery complete was not expected");

			}

			@Override
			public void authPacketArrived(int reasonCode, MqttProperties properties) {
				fail("delivery complete was not expected");

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
