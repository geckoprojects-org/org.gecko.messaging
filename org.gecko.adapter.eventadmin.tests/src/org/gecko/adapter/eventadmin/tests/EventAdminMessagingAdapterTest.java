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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.gecko.adapter.eventadmin.context.EventAdminMessagingContext;
import org.gecko.adapter.eventadmin.context.EventAdminMessagingContextBuilder;
import org.gecko.osgi.messaging.Message;
import org.gecko.osgi.messaging.MessagingContext;
import org.gecko.osgi.messaging.MessagingService;
import org.gecko.osgi.messaging.SimpleMessagingContextBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.event.EventHandler;
import org.osgi.test.common.annotation.InjectService;
import org.osgi.test.common.service.ServiceAware;
import org.osgi.test.junit5.context.BundleContextExtension;
import org.osgi.test.junit5.service.ServiceExtension;
import org.osgi.util.pushstream.PushStream;

@ExtendWith(MockitoExtension.class)
@ExtendWith(ServiceExtension.class)
@ExtendWith(BundleContextExtension.class)
public class EventAdminMessagingAdapterTest {

	@InjectService
	EventAdmin ea;
	
	@Test
	public void basicTest(@InjectService ServiceAware<MessagingService> msAware) throws Exception {
		ExecutorService es = Executors.newCachedThreadPool();
		assertFalse(msAware.isEmpty());
		MessagingService messagingService = msAware.getService();
		
		PushStream<Message> subscribe = messagingService.subscribe("test/topic");
		
		int messages = 100;
		
		CountDownLatch latch = new CountDownLatch(messages);
		
		subscribe.onError(t -> {
			System.err.println(t.getMessage());
			t.printStackTrace();
		});
		
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
		
		assertTrue(latch.await(10, TimeUnit.SECONDS), "Not all messages have been prcessed. Current count " + latch.getCount());
	}

	@Test
	public void basicTestSendViaMessageAdapter(@InjectService ServiceAware<MessagingService> msAware) throws Exception {
		ExecutorService es = Executors.newCachedThreadPool();
		assertFalse(msAware.isEmpty());
		MessagingService messagingService = msAware.getService();
		
		PushStream<Message> subscribe = messagingService.subscribe("test/topic");
		
		int messages = 100;
		
		CountDownLatch latch = new CountDownLatch(messages);
		
		subscribe.onError(t -> {
			System.err.println(t.getMessage());
			t.printStackTrace();
		});
		
		subscribe.adjustBackPressure((m,bp)->{
			System.out.println("bp: " + bp);
			return bp;
		}).fork(5, 0, es).forEach(m -> {
			String message = new String(m.payload().array());
			assertTrue(message.startsWith("test"));
			latch.countDown();
			System.out.println("sub content: " + message + " ts: " + System.currentTimeMillis());
		});
		
		Executors.newSingleThreadExecutor().execute(() -> {
			for(int i = 0; i < messages; i++) {
				try {
//					Event event = new Event("test/topic", Collections.singletonMap("content", ByteBuffer.wrap(("test" + i).getBytes())));
//					ea.postEvent(event);
//					Thread.sleep(10);
					messagingService.publish("test/topic", ByteBuffer.wrap(("test" + i).getBytes()));
				} catch (Exception e) {
					assertNull(e);
				}
			}
		});
		
		assertTrue(latch.await(10, TimeUnit.SECONDS), "Not all messages have been prcessed. Current count " + latch.getCount());
	}
	
	@Test
	public void basicTestSendViaMessageAdapterWithContext(@InjectService ServiceAware<MessagingService> msAware) throws Exception {
		assertFalse(msAware.isEmpty());
		MessagingService messagingService = msAware.getService();
		
		PushStream<Message> subscribe = messagingService.subscribe("test/topic");
		
		int messages = 100;
		
		CountDownLatch latch = new CountDownLatch(messages);
		
		subscribe.onError(t -> {
			System.err.println(t.getMessage());
			t.printStackTrace();
		});
		
		subscribe.forEach(m -> {
			String message = new String(m.payload().array());
			assertTrue(message.startsWith("test"));
			String contentType = m.getContext().getContentType();
			assertEquals("test", contentType);
			assertTrue(m.getContext() instanceof EventAdminMessagingContext);
			assertEquals("testheader", ((EventAdminMessagingContext) m.getContext()).getHeaders().get("testheader"));
			latch.countDown();
			System.out.println("sub content: " + message + " ts: " + System.currentTimeMillis());
		});
		
		MessagingContext build = EventAdminMessagingContextBuilder.builder().header("testheader", "testheader").contentType("test").build();
		
		Executors.newSingleThreadExecutor().execute(() -> {
			for(int i = 0; i < messages; i++) {
				try {
//					Event event = new Event("test/topic", Collections.singletonMap("content", ByteBuffer.wrap(("test" + i).getBytes())));
//					ea.postEvent(event);
//					Thread.sleep(10);
					messagingService.publish("test/topic", ByteBuffer.wrap(("test" + i).getBytes()),  build);
				} catch (Exception e) {
					assertNull(e);
				}
			}
		});
		
		assertTrue(latch.await(10, TimeUnit.SECONDS), "Not all messages have been prcessed. Current count " + latch.getCount());
	}

	@Test
	public void basicBackpressure(@InjectService ServiceAware<MessagingService> msAware) throws Exception {
		ExecutorService es = Executors.newCachedThreadPool();
		assertFalse(msAware.isEmpty());
		MessagingService messagingService = msAware.getService();
		
		PushStream<Message> subscribe = messagingService.subscribe("test/topic");
		
		int messages = 2;
		
		CountDownLatch latch = new CountDownLatch(messages);
		
		subscribe.onError(t -> {
			System.err.println(t.getMessage());
			t.printStackTrace();
		});
		
		subscribe.adjustBackPressure((m,bp)->{
			System.out.println("bp: " + bp);
			return 1000 * 5;
		}).fork(5, 0, es).forEach(m -> {
			String message = new String(m.payload().array());
			assertTrue(message.startsWith("test"));
			latch.countDown();
			System.out.println("sub content: " + message + " ts: " + System.currentTimeMillis());
		});
		
		MessagingContext build = SimpleMessagingContextBuilder.builder().contentType("test").build();
		
		Executors.newSingleThreadExecutor().execute(() -> {
			for(int i = 0; i < messages; i++) {
				try {
					messagingService.publish("test/topic", ByteBuffer.wrap(("test" + i).getBytes()),  build);
				} catch (Exception e) {
					assertNull(e);
				}
			}
		});
		
		assertTrue(latch.await(15, TimeUnit.SECONDS), "Not all messages have been prcessed. Current count " + latch.getCount());
	}
	
	@Test
	public void testCleanup(@InjectService ServiceAware<MessagingService> msAware, @InjectService(cardinality = 0)ServiceAware<EventHandler> ehAware) throws Exception {
		assertFalse(msAware.isEmpty());
		MessagingService messagingService = msAware.getService();
		
		PushStream<Message> subscribe = messagingService.subscribe("test/topic");
		
		int messages = 100;
		
		CountDownLatch latch = new CountDownLatch(messages);
		
		subscribe.onError(t -> {
			System.err.println(t.getMessage());
			t.printStackTrace();
		});
		
		subscribe.forEach(m -> {
			String message = new String(m.payload().array());
			assertTrue(message.startsWith("test"));
			latch.countDown();
			System.out.println("sub content: " + message + " ts: " + System.currentTimeMillis());
		});
		
		Executors.newSingleThreadExecutor().execute(() -> {
			for(int i = 0; i < messages; i++) {
				try {
					messagingService.publish("test/topic", ByteBuffer.wrap(("test" + i).getBytes()));
				} catch (Exception e) {
					assertNull(e);
				}
			}
		});
		
		assertTrue(latch.await(10, TimeUnit.SECONDS), "Not all messages have been prcessed. Current count " + latch.getCount());
		assertEquals(1, ehAware.getTrackingCount());
		subscribe.close();
		assertEquals(2, ehAware.getTrackingCount());
		
	}

	@Test
	public void testCleanupMultiple(@InjectService ServiceAware<MessagingService> msAware, @InjectService(cardinality = 0)ServiceAware<EventHandler> ehAware) throws Exception {
		assertFalse(msAware.isEmpty());
		MessagingService messagingService = msAware.getService();
		
		int messages = 100;
		
		assertEquals(0, ehAware.getTrackingCount());

		PushStream<Message> subscribe = messagingService.subscribe("test/topic");
		
		CountDownLatch latch = new CountDownLatch(messages);
		
		subscribe.onError(t -> {
			System.err.println(t.getMessage());
			t.printStackTrace();
		});
		
		subscribe.forEach(m -> {
			String message = new String(m.payload().array());
			assertTrue(message.startsWith("test"));
			latch.countDown();
			System.out.println("sub content: " + message + " ts: " + System.currentTimeMillis());
		});

		PushStream<Message> subscribe2 = messagingService.subscribe("test/topic");
//		assertEquals(1, ehAware.getTrackingCount());
		
		CountDownLatch latch2 = new CountDownLatch(messages);
		
		subscribe2.onError(t -> {
			System.err.println(t.getMessage());
			t.printStackTrace();
		});
		
		subscribe2.forEach(m -> {
			String message = new String(m.payload().array());
			assertTrue(message.startsWith("test"));
			latch2.countDown();
			System.out.println("sub content: " + message + " ts: " + System.currentTimeMillis());
		});

		PushStream<Message> subscribe3 = messagingService.subscribe("test/topic");
		assertEquals(1, ehAware.getTrackingCount());
		
		CountDownLatch latch3 = new CountDownLatch(messages);
		
		subscribe3.onError(t -> {
			System.err.println(t.getMessage());
			t.printStackTrace();
		});
		
		subscribe3.forEach(m -> {
			String message = new String(m.payload().array());
			assertTrue(message.startsWith("test"));
			latch3.countDown();
			System.out.println("sub content: " + message + " ts: " + System.currentTimeMillis());
		});
		
		Executors.newSingleThreadExecutor().execute(() -> {
			for(int i = 0; i < messages; i++) {
				try {
					messagingService.publish("test/topic", ByteBuffer.wrap(("test" + i).getBytes()));
				} catch (Exception e) {
					assertNull(e);
				}
			}
		});
		
		assertTrue(latch.await(10, TimeUnit.SECONDS), "Not all messages have been processed. Current count " + latch.getCount());
		assertTrue(latch2.await(10, TimeUnit.SECONDS), "Not all messages have been processed. Current count " + latch2.getCount());
		assertTrue(latch3.await(10, TimeUnit.SECONDS), "Not all messages have been processed. Current count " + latch3.getCount());
		
		subscribe.close();
//		assertEquals(1, ehAware.getTrackingCount());

		subscribe2.close();
//		assertEquals(1, ehAware.getTrackingCount());
		
		subscribe3.close();
//		assertEquals(0, ehAware.getTrackingCount());
	}

}