package org.gecko.adapter.amqp.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.gecko.adapter.amqp.client.AMQPClient;
import org.gecko.adapter.amqp.client.AMQPContextBuilder;
import org.gecko.osgi.messaging.Message;
import org.gecko.osgi.messaging.MessagingConstants;
import org.gecko.osgi.messaging.MessagingContext;
import org.gecko.osgi.messaging.MessagingService;
import org.gecko.util.pushstream.PushStreamConstants;
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
import org.osgi.util.promise.PromiseFactory;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.QueuePolicyOption;
import org.osgi.util.tracker.ServiceTracker;

@ExtendWith(MockitoExtension.class)
@ExtendWith(BundleContextExtension.class)
public class AMQPComponentSubscribeTest {

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
	 * 
	 * @throws Exception
	 */
	@Test
	public void testSubscribeMessage() throws Exception {
		final CountDownLatch createLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "AMQPService", createLatch);

		String publishTopic = "test_q";
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
		MessagingService messagingService = getService(MessagingService.class, 30000l);
		assertNotNull(messagingService);
		PushStream<Message> subscribe = messagingService.subscribe(publishTopic);
		subscribe.forEach((m) -> {
			String r = new String(m.payload().array());
			result.set(r);
			resultLatch.countDown();
		});

		checkClient.sendSingleWithQueue(publishTopic, publishContent);
		// wait and compare the received message
		resultLatch.await(15, TimeUnit.SECONDS);
		assertEquals(publishContent, result.get());
	}
	
	/**
	 * Tests subscribing to a message using fanout multicast
	 * https://www.rabbitmq.com/tutorials/tutorial-four-java.html
	 * @throws Exception
	 */
	@Test
	public void testSubscribeFanout() throws Exception {
		final CountDownLatch createLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "AMQPService", createLatch);
		
		String publishTopic = "test_fan";
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
		
		// starting adapter with the given properties
		clientConfig.update(p);
		
		createLatch.await(2, TimeUnit.SECONDS);
		
		// check for service
		MessagingService messagingService = getService(MessagingService.class, 30000l);
		assertNotNull(messagingService);
		MessagingContext ctx = new AMQPContextBuilder().fanout().exchange(publishTopic, "").build();
		PushStream<Message> subscribe01 = messagingService.subscribe(publishTopic, ctx);
		subscribe01.forEach((m) -> {
			String r = new String(m.payload().array());
			result01.set(r);
			resultLatch.countDown();
		});
		AMQPClient c2 = new AMQPClient(amqpHost);
		c2.registerConsumerFanout(publishTopic, (b)->{
			String r = new String(b);
			result02.set(r);
			resultLatch.countDown();
		});
		
		checkClient.sendSingleWithFanout(publishTopic, publishContent);
		// wait and compare the received message
		assertTrue(resultLatch.await(15, TimeUnit.SECONDS));
		assertEquals(publishContent, result01.get());
		assertEquals(publishContent, result02.get());
	}
	
	/**
	 * Tests subscribing to a message using fanout multicast
	 * https://www.rabbitmq.com/tutorials/tutorial-four-java.html
	 * @throws Exception
	 */
	@Test
	public void testSubscribeDirectMulticast() throws Exception {
		final CountDownLatch createLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "AMQPService", createLatch);
		
		String publishTopic = "test_dir";
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
		
		// starting adapter with the given properties
		clientConfig.update(p);
		
		createLatch.await(2, TimeUnit.SECONDS);
		
		// check for service
		MessagingService messagingService = getService(MessagingService.class, 30000l);
		assertNotNull(messagingService);
		MessagingContext ctx = new AMQPContextBuilder().durable().direct().exchange(publishTopic, "blub").build();
		PushStream<Message> subscribe01 = messagingService.subscribe(publishTopic, ctx);
		subscribe01.forEach((m) -> {
			String r = new String(m.payload().array());
			result01.set(r);
			resultLatch.countDown();
		});
		AMQPClient c2 = new AMQPClient(amqpHost);
		c2.registerConsumerDirect(publishTopic, "blub", (b)->{
			String r = new String(b);
			result02.set(r);
			resultLatch.countDown();
		});
		
		checkClient.sendSingleWithExchangeDirect(publishTopic, "blub", publishContent);
		// wait and compare the received message
		assertTrue(resultLatch.await(15, TimeUnit.SECONDS));
		assertEquals(publishContent, result01.get());
		assertEquals(publishContent, result02.get());
	}
	
	/**
	 * Tests subscribing to a message using fanout multicast
	 * https://www.rabbitmq.com/tutorials/tutorial-four-java.html
	 * @throws Exception
	 */
	@Test
	public void testSubscribeDirectSelective() throws Exception {
		final CountDownLatch createLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "AMQPService", createLatch);
		
		String publishTopic = "test_dir";
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
		AtomicReference<String> result01 = new AtomicReference<>();
		AtomicReference<String> result02 = new AtomicReference<>();
		
		// starting adapter with the given properties
		clientConfig.update(p);
		
		createLatch.await(2, TimeUnit.SECONDS);
		
		// check for service
		MessagingService messagingService = getService(MessagingService.class, 30000l);
		assertNotNull(messagingService);
		MessagingContext ctx = new AMQPContextBuilder().durable().direct().exchange(publishTopic, "bla").build();
		PushStream<Message> subscribe01 = messagingService.subscribe(publishTopic, ctx);
		subscribe01.forEach((m) -> {
			String r = new String(m.payload().array());
			result01.set(r);
			resultLatch.countDown();
		});
		AMQPClient c2 = new AMQPClient(amqpHost);
		c2.registerConsumerDirect(publishTopic, "blub", (b)->{
			String r = new String(b);
			result02.set(r);
			resultLatch.countDown();
		});
		
		checkClient.sendSingleWithExchangeDirect(publishTopic, "blub", publishContent);
		// wait and compare the received message
		assertTrue(resultLatch.await(15, TimeUnit.SECONDS));
		assertNull(result01.get());
		assertEquals(publishContent, result02.get());
	}
	
	/**
	 * Tests publishing a message
	 * 
	 * @throws Exception
	 */
	@Test
	public void testSubscribeMessageEnv() throws Exception {
		final CountDownLatch createLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "AMQPService", createLatch);
		
		String publishTopic = "test_q";
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
		MessagingService messagingService = getService(MessagingService.class, 30000l);
		assertNotNull(messagingService);
		PushStream<Message> subscribe = messagingService.subscribe(publishTopic);
		subscribe.forEach((m) -> {
			String r = new String(m.payload().array());
			result.set(r);
			resultLatch.countDown();
		});
		
		checkClient.sendSingleWithQueue(publishTopic, publishContent);
		// wait and compare the received message
		resultLatch.await(15, TimeUnit.SECONDS);
		assertEquals(publishContent, result.get());
	}
	
	/**
	 * Tests publishing a message
	 * 
	 * @throws Exception
	 */
	@Test
	public void testSubscribeMessageConfigureEventSource() throws Exception {
		final CountDownLatch createLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "AMQPService", createLatch);
		
		String publishTopic = "test_q";
		String publishContent = "this is an AMQP test";
		
		// has to be a new configuration
		Dictionary<String, Object> p = clientConfig.getProperties();
		assertNull(p);
		// add service properties
		p = new Hashtable<>();
//		p.put(MessagingConstants.PROP_PUBLISH_TOPICS, publishTopic);
		p.put(MessagingConstants.PROP_BROKER, brokerUrl);
		p.put(PushStreamConstants.PROP_SES_BUFFER_SIZE, 100);
		p.put(PushStreamConstants.PROP_SES_QUEUE_POLICY_BY_NAME, QueuePolicyOption.BLOCK.name());
		
		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(1);
		// holder for the result
		AtomicReference<String> result = new AtomicReference<>();
		
		// starting adapter with the given properties
		clientConfig.update(p);
		
		createLatch.await(2, TimeUnit.SECONDS);
		
		// check for service
		MessagingService messagingService = getService(MessagingService.class, 30000l);
		assertNotNull(messagingService);
		PushStream<Message> subscribe = messagingService.subscribe(publishTopic);
		subscribe.forEach((m) -> {
			String r = new String(m.payload().array());
			result.set(r);
			resultLatch.countDown();
		});
		
		checkClient.sendSingleWithQueue(publishTopic, publishContent);
		// wait and compare the received message
		resultLatch.await(15, TimeUnit.SECONDS);
		assertEquals(publishContent, result.get());
	}

	/**
	 * Tests publishing a message
	 * 
	 * @throws Exception
	 */
	@Test
	public void testSubscribeMessageMany() throws Exception {
		final CountDownLatch createLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "AMQPService", createLatch);

		String publishTopic = "test_q";
		String publishContent = "this is an AMQP test";

		// has to be a new configuration
		Dictionary<String, Object> p = clientConfig.getProperties();
		assertNull(p);
		// add service properties
		p = new Hashtable<>();
//		p.put(MessagingConstants.PROP_PUBLISH_TOPICS, publishTopic);
		p.put(MessagingConstants.PROP_BROKER, brokerUrl);
		p.put("jmx", true);

		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(10);
		// holder for the result
		AtomicReference<List<String>> result = new AtomicReference<>();
		result.set(new ArrayList<String>(10));
		// starting adapter with the given properties
		clientConfig.update(p);

		createLatch.await(10, TimeUnit.SECONDS);

		// check for service
		MessagingService messagingService = getService(MessagingService.class, 30000l);
		assertNotNull(messagingService);

		PushStream<Message> subscribe = messagingService.subscribe(publishTopic);
		subscribe.forEach((m) -> {
			String r = new String(m.payload().array());
			result.get().add(r);
			resultLatch.countDown();
		});

		for (int i = 0; i < 10; i++) {
			checkClient.sendSingleWithQueue(publishTopic, publishContent + i);
		}

		// wait and compare the received message
		resultLatch.await(25, TimeUnit.SECONDS);
		assertEquals(10, result.get().size());
		assertEquals(publishContent + "9", result.get().get(9));
	}

	/**
	 * Tests publishing a message
	 * 
	 * @throws Exception
	 */
	@Test
	public void testSubscribeManyMessageMany() throws Exception {
		final CountDownLatch createLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "AMQPService", createLatch);

		String publishTopic = "test_dir";
		String publishContent = "this is an AMQP test";

		// has to be a new configuration
		Dictionary<String, Object> p = clientConfig.getProperties();
		assertNull(p);
		// add service properties
		p = new Hashtable<>();
//		p.put(MessagingConstants.PROP_PUBLISH_TOPICS, publishTopic);
		p.put(MessagingConstants.PROP_BROKER, brokerUrl);

		// count down latch to wait for the message
		CountDownLatch resultLatch = new CountDownLatch(20);
		// holder for the result
		AtomicReference<List<String>> result = new AtomicReference<>();
		result.set(new ArrayList<String>(20));
		// starting adapter with the given properties
		clientConfig.update(p);

		createLatch.await(2, TimeUnit.SECONDS);

		// check for service
		MessagingService messagingService = getService(MessagingService.class, 30000l);
		assertNotNull(messagingService);
		AMQPContextBuilder builder = new AMQPContextBuilder();
		ExecutorService es = Executors.newCachedThreadPool();
		MessagingContext ctx = builder.direct().exchange(publishTopic, "test").durable().withParallelism(2)
				.withExecutor(es).build();
		PushStream<Message> subscribe01 = messagingService.subscribe(publishTopic, ctx);
		ctx = builder.direct().exchange(publishTopic, "test").durable().withParallelism(2).withExecutor(es).build();
		PushStream<Message> subscribe02 = messagingService.subscribe(publishTopic, ctx);

//		PromiseFactory pf = new PromiseFactory(es);
//		Promise<Integer> countPromise = pf.submit(()->{
//			if (resultLatch.await(10, TimeUnit.SECONDS) ) {
//				return result.get().size();
//			} else {
//				throw new IllegalStateException("Timeout waiting");
//			}
//		});

		subscribe01.forEach((m) -> {
			String r = new String(m.payload().array());
			List<String> list = result.get();
			synchronized (list) {
				list.add(r + "1");
			}
			resultLatch.countDown();
		});
		subscribe02.forEach((m) -> {
			String r = new String(m.payload().array());
			List<String> list = result.get();
			synchronized (list) {
				list.add(r + "2");
			}
			resultLatch.countDown();
		});

		PromiseFactory pf = new PromiseFactory(es);
		Promise<Integer> sendPromise = pf.submit(() -> {
			for (int i = 0; i < 10; i++) {
				checkClient.sendSingleWithExchangeDirect(publishTopic, "test", publishContent + i);
			}
			return 10;
		});
		assertEquals(10, sendPromise.getValue().intValue());
		resultLatch.await(10, TimeUnit.SECONDS);
		assertEquals(20, result.get().size());
//		countPromise.thenAccept((i)->assertEquals(20, i.intValue())).onFailure(t->fail("Test failed with " + t));
		// wait and compare the received message
//		countPromise.getValue();
	}

	/**
	 * Tests publishing a message
	 * 
	 * @throws Exception
	 */
	@Test
	public void testSubscribeMessageWrongQueue() throws Exception {
		final CountDownLatch createLatch = new CountDownLatch(1);
		clientConfig = getConfiguration(context, "AMQPService", createLatch);

		String publishTopic = "test_q";
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
		MessagingService messagingService = getService(MessagingService.class, 30000l);
		assertNotNull(messagingService);
		PushStream<Message> subscribe = messagingService.subscribe(publishTopic);
		subscribe.forEach((m) -> {
			String r = new String(m.payload().array());
			result.set(r);
			resultLatch.countDown();
		});

		checkClient.sendSingleWithQueue(publishTopic + "test", publishContent);
		// wait and compare the received message
		boolean countedDown = resultLatch.await(5, TimeUnit.SECONDS);
		assertFalse(countedDown);
		assertNull(result.get());
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
