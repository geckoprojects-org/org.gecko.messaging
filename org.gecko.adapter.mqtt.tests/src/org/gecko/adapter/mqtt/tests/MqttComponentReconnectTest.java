package org.gecko.adapter.mqtt.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.gecko.moquette.broker.MQTTBroker;
import org.gecko.osgi.messaging.Message;
import org.gecko.osgi.messaging.MessagingConstants;
import org.gecko.osgi.messaging.MessagingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.service.cm.annotations.RequireConfigurationAdmin;
import org.osgi.test.common.annotation.InjectService;
import org.osgi.test.common.annotation.Property;
import org.osgi.test.common.annotation.config.WithFactoryConfiguration;
import org.osgi.test.common.service.ServiceAware;
import org.osgi.test.junit5.cm.ConfigurationExtension;
import org.osgi.test.junit5.context.BundleContextExtension;
import org.osgi.test.junit5.service.ServiceExtension;

@RequireConfigurationAdmin
@ExtendWith(MockitoExtension.class)
@ExtendWith(ServiceExtension.class)
@ExtendWith(ConfigurationExtension.class)
@ExtendWith(BundleContextExtension.class)
public class MqttComponentReconnectTest {

	private static final String TOPIC = "publish.junit." + UUID.randomUUID();
	private static final String BROKER_URL = "tcp://localhost:2183";
	private CountDownLatch messageLatch;


	/**
	 * Tests reconnect client
	 * 
	 * @throws Exception
	 */
	@Test
	@WithFactoryConfiguration(factoryPid = "MQTTBroker", location = "?", name = "broker", properties = {
			@Property(key = MQTTBroker.HOST, value = "localhost"),
			@Property(key = MQTTBroker.PORT, value = "2183") })
	@WithFactoryConfiguration(factoryPid = "MQTTService", location = "?", name = "read", properties = {
			@Property(key = MessagingConstants.PROP_BROKER, value = BROKER_URL) })
	@WithFactoryConfiguration(factoryPid = "MQTTService", location = "?", name = "write", properties = {
			@Property(key = MessagingConstants.PROP_BROKER, value = BROKER_URL) })
	public void testReconnect(@InjectService(cardinality = 0) ServiceAware<MQTTBroker> bAware,
			@InjectService(cardinality = 0) ServiceAware<MessagingService> read, @InjectService(cardinality = 0) ServiceAware<MessagingService> write) throws Exception {

		messageLatch = new CountDownLatch(1);

		MQTTBroker broker = bAware.getService();
		
		MessagingService readMessagingService = read.getService();
		assertNotNull(readMessagingService);
		readMessagingService.subscribe(TOPIC).forEach(m -> handle(m));

		MessagingService writeMessageService = write.getService();
		assertNotNull(writeMessageService);
		TestPublisher publisher = new TestPublisher(writeMessageService);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			// start publishing
			executor.execute(publisher);
			messageLatch.await(1, TimeUnit.SECONDS);
			publisher.waitSuccess(1);
			assertTrue(publisher.isPublishing);

			// stop Server
			broker.stop();
			publisher.waitError(3);
			assertFalse(publisher.isPublishing);

			// start Server
			broker.start();

			// waiting for reconnect
			publisher.waitSuccess(3);
			messageLatch = new CountDownLatch(1);
			messageLatch.await(3, TimeUnit.SECONDS);
			assertTrue(publisher.isPublishing);
		} finally {
			executor.shutdownNow();
		}
	}

	private void handle(Message msg) {
		String v = new String(msg.payload().array());
		assertEquals("42", v);
		messageLatch.countDown();
	}

	private static final class TestPublisher implements Runnable {
		private final MessagingService messagingService;
		private boolean isPublishing;
		private final CountDownLatch successLatch;
		private CountDownLatch errorLatch;

		private TestPublisher(MessagingService messagingService) {
			this.successLatch = new CountDownLatch(1);
			this.errorLatch = new CountDownLatch(1);
			this.messagingService = messagingService;
		}

		@Override
		public void run() {
			isPublishing = true;
			while (true) {
				try {
					messagingService.publish(TOPIC, ByteBuffer.wrap("42".getBytes()));
					if (isPublishing == false) {
						isPublishing = true;
						successLatch.countDown();
					}
				} catch (Exception e) {
					if (isPublishing == true) {
						isPublishing = false;
						errorLatch.countDown();
					}
				}
				try {
					Thread.sleep(10);
				} catch (InterruptedException e) {
				}
			}
		}

		private void waitError(int seconds) throws InterruptedException {
			errorLatch.await(seconds, TimeUnit.SECONDS);
		}

		private void waitSuccess(int seconds) throws InterruptedException {
			successLatch.await(seconds, TimeUnit.SECONDS);
		}
	}

}
