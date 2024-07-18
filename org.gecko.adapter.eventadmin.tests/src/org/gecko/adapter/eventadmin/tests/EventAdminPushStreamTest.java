/*
 * Copyright (c) 2012 - 2024 Data In Motion and others.
 * All rights reserved. 
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Data In Motion - initial API and implementation
 */

package org.gecko.adapter.eventadmin.tests;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.gecko.osgi.messaging.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceObjects;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.cm.annotations.RequireConfigurationAdmin;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.annotations.RequireEventAdmin;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.common.annotation.InjectService;
import org.osgi.test.common.service.ServiceAware;
import org.osgi.test.junit5.context.BundleContextExtension;
import org.osgi.test.junit5.service.ServiceExtension;
import org.osgi.util.pushstream.PushStream;

@RequireEventAdmin
@RequireConfigurationAdmin
@ExtendWith(MockitoExtension.class)
@ExtendWith(ServiceExtension.class)
@ExtendWith(BundleContextExtension.class)
public class EventAdminPushStreamTest {

	@InjectBundleContext
	BundleContext context;
	@InjectService
	EventAdmin ea;
	@InjectService
	ConfigurationAdmin ca;
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Test
	public void basicTest(@InjectService(cardinality = 0)ServiceAware<PushStream> psAware, @InjectService(cardinality = 0)ServiceAware<EventHandler> ehAware) throws Exception {
		ExecutorService es = Executors.newCachedThreadPool();
		
		Dictionary<String, Object> props = new Hashtable<>();
		
		props.put("topic", "test/topic");
		
		assertTrue(psAware.isEmpty());
		
		
		Configuration createConfigForCleanup = ca.createFactoryConfiguration("EventAdminTopicSubscription", "?");
		createConfigForCleanup.update(props);

		assertNotNull(psAware.waitForService(2000l));
		assertFalse(psAware.isEmpty());

		PushStream<Message> subscribe = psAware.getService();
		
		subscribe.onError(t -> {
			System.err.println(String.format("[%s] ERROR BUFFER ", System.currentTimeMillis()));
			System.err.println(t.getMessage());
			t.printStackTrace();
		});
		
		int messages = 2000;
		
		CountDownLatch latch = new CountDownLatch(messages);
		
		subscribe.adjustBackPressure((m,bp)->{
			System.out.println("bp: " + bp);
			return bp;
		}).fork(5, 0, es).forEach(m -> {
//			es.submit(() -> {
				
				String message = new String(m.payload().array());
				assertTrue(message.startsWith("test"));
				latch.countDown();
				System.out.println("sub content: " + message + " ts: " + System.currentTimeMillis());
//			});
		});

		Executors.newSingleThreadExecutor().execute(() -> {
			System.err.println(String.format("[%s] START POSTING ", System.currentTimeMillis()));
			for(int i = 0; i < messages; i++) {
				try {
					Event event = new Event("test/topic", Collections.singletonMap("content", ByteBuffer.wrap(("test" + i).getBytes())));
					ea.postEvent(event);
//					Thread.sleep(10);
				} catch (Exception e) {
					assertNull(e);
				}
			}
			System.err.println(String.format("[%s] FINISHED POSTING ", System.currentTimeMillis()));
		});

		assertTrue(latch.await(10, TimeUnit.SECONDS), "Not all messages have been prcessed. Current count " + latch.getCount());

		
		assertEquals(1, ehAware.getTrackingCount());
		subscribe.close();
		assertEquals(1, ehAware.getTrackingCount());
		
		createConfigForCleanup.delete();
		assertEquals(1, ehAware.getTrackingCount());
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Test
	public void basicTestMultipleStreams(@InjectService(cardinality = 0)ServiceAware<PushStream> psAware, @InjectService(cardinality = 0)ServiceAware<EventHandler> ehAware) throws Exception {
		Dictionary<String, Object> props = new Hashtable<>();
		
		props.put("topic", "test/topic");
		
		assertTrue(psAware.isEmpty());
		
		Configuration createConfigForCleanup = ca.createFactoryConfiguration("EventAdminTopicSubscription", "?");
		createConfigForCleanup.update(props);
		
		assertNotNull(psAware.waitForService(2000l));
		assertFalse(psAware.isEmpty());
		
		ServiceObjects<PushStream> serviceObjects = context.getServiceObjects(psAware.getServiceReference());
		PushStream<Message> subscribe1 = serviceObjects.getService();
		PushStream<Message> subscribe2 = serviceObjects.getService();
		PushStream<Message> subscribe3 = serviceObjects.getService();
		PushStream<Message> subscribe4 = serviceObjects.getService();
		PushStream<Message> subscribe5 = serviceObjects.getService();
		PushStream<Message> subscribe6 = serviceObjects.getService();
		
		subscribe1.onError(t -> {
			System.err.println(t.getMessage());
			t.printStackTrace();
		});
		subscribe2.onError(t -> {
			System.err.println(t.getMessage());
			t.printStackTrace();
		});
		subscribe3.onError(t -> {
			System.err.println(t.getMessage());
			t.printStackTrace();
		});
		subscribe4.onError(t -> {
			System.err.println(t.getMessage());
			t.printStackTrace();
		});
		subscribe5.onError(t -> {
			System.err.println(t.getMessage());
			t.printStackTrace();
		});
		subscribe6.onError(t -> {
			System.err.println(t.getMessage());
			t.printStackTrace();
		});
		
		assertNotEquals(subscribe1, subscribe2);
		
		int messages = 100;
		
		CountDownLatch latch1 = new CountDownLatch(messages);
		CountDownLatch latch2 = new CountDownLatch(messages);
		CountDownLatch latch3 = new CountDownLatch(messages);
		CountDownLatch latch4 = new CountDownLatch(messages);
		CountDownLatch latch5 = new CountDownLatch(messages);
		CountDownLatch latch6 = new CountDownLatch(messages);
		
		CountDownLatch closeCounter = new CountDownLatch(6);
		
		subscribe1.onClose(() -> closeCounter.countDown());
		subscribe2.onClose(() -> closeCounter.countDown());
		subscribe3.onClose(() -> closeCounter.countDown());
		subscribe4.onClose(() -> closeCounter.countDown());
		subscribe5.onClose(() -> closeCounter.countDown());
		subscribe6.onClose(() -> closeCounter.countDown());
		
		subscribe1.forEach(m -> {
			String message = new String(m.payload().array());
			assertTrue(message.startsWith("test"));
			latch1.countDown();
			System.out.println("sub content: " + message + " ts: " + System.currentTimeMillis());
		});

		subscribe2.forEach(m -> {
			String message = new String(m.payload().array());
			assertTrue(message.startsWith("test"));
			latch2.countDown();
			System.out.println("sub content: " + message + " ts: " + System.currentTimeMillis());
		});
		subscribe3.forEach(m -> {
			String message = new String(m.payload().array());
			assertTrue(message.startsWith("test"));
			latch3.countDown();
			System.out.println("sub content: " + message + " ts: " + System.currentTimeMillis());
		});
		subscribe4.forEach(m -> {
			String message = new String(m.payload().array());
			assertTrue(message.startsWith("test"));
			latch4.countDown();
			System.out.println("sub content: " + message + " ts: " + System.currentTimeMillis());
		});
		subscribe5.forEach(m -> {
			String message = new String(m.payload().array());
			assertTrue(message.startsWith("test"));
			latch5.countDown();
			System.out.println("sub content: " + message + " ts: " + System.currentTimeMillis());
		});
		subscribe6.forEach(m -> {
			String message = new String(m.payload().array());
			assertTrue(message.startsWith("test"));
			latch6.countDown();
			System.out.println("sub content: " + message + " ts: " + System.currentTimeMillis());
		});
		
		Executors.newSingleThreadExecutor().execute(() -> {
			for(int i = 0; i < messages; i++) {
				try {
					Event event = new Event("test/topic", Collections.singletonMap("content", ByteBuffer.wrap(("test" + i).getBytes())));
					ea.postEvent(event);
//					Thread.sleep(10);
				} catch (Exception e) {
					assertNull(e);
				}
			}
		});
		
		assertTrue(latch1.await(10, TimeUnit.SECONDS), "Not all messages have been prcessed. Current count " + latch1.getCount());
		assertTrue(latch2.await(10, TimeUnit.SECONDS), "Not all messages have been prcessed. Current count " + latch2.getCount());
		assertTrue(latch3.await(10, TimeUnit.SECONDS), "Not all messages have been prcessed. Current count " + latch3.getCount());
		assertTrue(latch4.await(10, TimeUnit.SECONDS), "Not all messages have been prcessed. Current count " + latch4.getCount());
		assertTrue(latch5.await(10, TimeUnit.SECONDS), "Not all messages have been prcessed. Current count " + latch5.getCount());
		assertTrue(latch6.await(10, TimeUnit.SECONDS), "Not all messages have been prcessed. Current count " + latch6.getCount());
		
		
		assertEquals(1, ehAware.getTrackingCount());
		subscribe1.close();
		assertEquals(1, ehAware.getTrackingCount());

		assertEquals(5, closeCounter.getCount());

		subscribe2.close();
		assertEquals(1, ehAware.getTrackingCount());
		assertEquals(4, closeCounter.getCount());
		
		createConfigForCleanup.delete();
		assertEquals(1, ehAware.getTrackingCount());
		assertTrue(closeCounter.await(1, TimeUnit.SECONDS));
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Test
	public void basicTestMultipleStreamsWithError(@InjectService(cardinality = 0)ServiceAware<PushStream> psAware, @InjectService(cardinality = 0)ServiceAware<EventHandler> ehAware) throws Exception {
		Dictionary<String, Object> props = new Hashtable<>();
		
		props.put("topic", "test/topic");
		
		assertTrue(psAware.isEmpty());

		Configuration createConfigForCleanup = ca.createFactoryConfiguration("EventAdminTopicSubscription", "?");
		createConfigForCleanup.update(props);
		
		psAware.waitForService(2000l);
		
		ServiceObjects<PushStream> serviceObjects = context.getServiceObjects(psAware.getServiceReference());
		PushStream<Message> subscribe1 = serviceObjects.getService();
		PushStream<Message> subscribe5 = serviceObjects.getService();
		
		assertNotEquals(subscribe1, subscribe5);
		
		int messages = 100;
		
		CountDownLatch latch1 = new CountDownLatch(messages);
		CountDownLatch latch5 = new CountDownLatch(messages);
		
		CountDownLatch closeCounter = new CountDownLatch(2);
		CountDownLatch errorCounter = new CountDownLatch(1);
		
		subscribe1.onClose(() -> closeCounter.countDown());
		subscribe5.onClose(() -> closeCounter.countDown());
		subscribe5.onError((t) -> {
			errorCounter.countDown();
		});
		
		subscribe1.forEach(m -> {
			String message = new String(m.payload().array());
			assertTrue(message.startsWith("test"));
			latch1.countDown();
			System.out.println("sub content: " + message + " ts: " + System.currentTimeMillis());
		});
		
		subscribe5.forEachEvent(e -> {
			Message m = e.getData();
			String message = new String(m.payload().array());
			assertTrue(message.startsWith("test"));
			latch5.countDown();
			System.out.println("sub content: " + message + " ts: " + System.currentTimeMillis());
			if(latch5.getCount() == 95) {
				throw new RuntimeException("ERROR");
			}
			return 0;
		});
		
		Executors.newSingleThreadExecutor().execute(() -> {
			for(int i = 0; i < messages; i++) {
				try {
					Event event = new Event("test/topic", Collections.singletonMap("content", ByteBuffer.wrap(("test" + i).getBytes())));
					ea.postEvent(event);
//					Thread.sleep(10);
				} catch (Exception e) {
					assertNull(e);
				}
			}
		});
		
		assertTrue(latch1.await(10, TimeUnit.SECONDS), "Not all messages have been prcessed. Current count " + latch1.getCount());
		
		assertEquals(95, latch5.getCount());
		
		assertEquals(1, closeCounter.getCount());
		assertEquals(0, errorCounter.getCount());

		assertEquals(1, ehAware.getTrackingCount());
		subscribe1.close();
		
		assertEquals(1, ehAware.getTrackingCount());
		assertTrue(closeCounter.await(1,  TimeUnit.SECONDS));
		
		createConfigForCleanup.delete();
		assertEquals(1, ehAware.getTrackingCount());
	}

}