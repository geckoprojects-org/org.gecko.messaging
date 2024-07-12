package org.gecko.adapter.mqtt.tests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.gecko.adapter.mqtt.MQTTContextBuilder;
import org.gecko.adapter.mqtt.QoS;
import org.gecko.moquette.broker.MQTTBroker;
import org.gecko.osgi.messaging.MessagingConstants;
import org.gecko.osgi.messaging.MessagingContext;
import org.gecko.osgi.messaging.MessagingService;
import org.gecko.osgi.messaging.SimpleMessagingContextBuilder;
import org.gecko.osgi.messaging.annotations.RequireMQTTv5;
import org.junit.jupiter.api.BeforeEach;
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
@RequireMQTTv5
@WithFactoryConfiguration(factoryPid = "MQTTBroker", location = "?", name = "broker", properties = {
		@Property(key = MQTTBroker.HOST, value = "localhost"), @Property(key = MQTTBroker.PORT, value = "2183") })
public class MqttComponentRetainedTest {

	
	private static final int TOPIC_COUNT = 4;
	private static final int MESSAGE_COUNT = 10000 / TOPIC_COUNT;
	private static final String TOPIC = "test.candelete";
	private static final String BROKER_URL = "tcp://localhost:2183";

	@BeforeEach
	@WithFactoryConfiguration(factoryPid = "MQTTService", location = "?", name = "write", properties = {
			@Property(key = MessagingConstants.PROP_USERNAME, value = "demo"),
			@Property(key = MessagingConstants.PROP_PASSWORD, value = "1234"),
			@Property(key = MessagingConstants.PROP_BROKER, value = BROKER_URL) })
	public void setup(@InjectService(cardinality = 0) ServiceAware<MessagingService> writeAware) throws Exception {
		MessagingService write = writeAware.waitForService(10000);
		for (int i = 0; i < TOPIC_COUNT; i++) {
			publish(write, TOPIC+i+"/");
		}
	}

	private void publish(MessagingService write, String t) throws Exception {
		MessagingContext ctx = new MQTTContextBuilder().retained().withQoS(QoS.AT_LEAST_ONE).build();
		for (int i = 0; i < MESSAGE_COUNT; i++) {
			String topic = t + "123456789012345678901234567890-" + i;
			write.publish(topic, ByteBuffer.wrap(("123456789-" + i).getBytes()), ctx);
			if(i % 100 == 0) {
				Thread.sleep(100); // To avoid Too many publishes in progress from moquette 
			}
		}
	}

	@Test
	@WithFactoryConfiguration(factoryPid = "MQTTService", location = "?", name = "read", properties = {
			@Property(key = MessagingConstants.PROP_BROKER, value = BROKER_URL) })
	public void testForward(@InjectService(cardinality = 0) MQTTBroker broker,
			@InjectService(cardinality = 0) ServiceAware<MessagingService> readAware) throws Exception {

		MessagingService readMessagingService = readAware.waitForService(10000);
		MessagingContext ctx = SimpleMessagingContextBuilder.builder().withBuffer(100 * MESSAGE_COUNT).build();

		CountDownLatch messageLatch = new CountDownLatch(MESSAGE_COUNT*TOPIC_COUNT);
		for (int i = 0; i < TOPIC_COUNT; i++) {
			sub(readMessagingService, ctx, TOPIC+i+"/#",messageLatch);
		}
		boolean result = messageLatch.await(10, TimeUnit.SECONDS);
		assertTrue(result, "Missing " + messageLatch.getCount() + " messages.");
		System.out.println();
	}

	private void sub(MessagingService readMessagingService, MessagingContext ctx, String topic, CountDownLatch messageLatch)
			throws Exception, InterruptedException {
		readMessagingService.subscribe(topic, ctx).forEach(m -> {
			System.out.println(m.topic());
			messageLatch.countDown();
			try {
				readMessagingService.publish("forward/" + m.topic(), m.payload());
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
	}

}
