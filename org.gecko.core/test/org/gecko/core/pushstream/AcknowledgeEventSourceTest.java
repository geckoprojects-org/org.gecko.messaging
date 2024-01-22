/**
 * Copyright (c) 2012 - 2018 Data In Motion and others.
 * All rights reserved. 
 * 
 * This program and the accompanying materials are made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
package org.gecko.core.pushstream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.gecko.core.pushstream.source.AcknowledgingEventSource;
import org.junit.jupiter.api.Test;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamProvider;

public class AcknowledgeEventSourceTest {

	@Test
	public void testAcknowledge() throws InterruptedException {
		AtomicInteger ackCnt = new AtomicInteger();
		AtomicInteger nackCnt = new AtomicInteger();
		AtomicInteger ackErrorCnt = new AtomicInteger();
		int NUMBER_EVENTS = 5;
		PushStreamProvider psp = new PushStreamProvider();
		AcknowledgingEventSource<String> spes = PushStreamHelper.fromClass(String.class, null);
		spes.acknowledge(s->ackCnt.incrementAndGet()).negativeAcknowledge(s->nackCnt.incrementAndGet()).acknowledgeError((t,s)->ackErrorCnt.incrementAndGet());
		
		
		CountDownLatch latch = new CountDownLatch(NUMBER_EVENTS);
		PushStream<String> ps = psp.buildStream(spes).build();
		ps.onError(Throwable::printStackTrace).forEach(s->{
			latch.countDown();
		});
		
		Executors.newSingleThreadExecutor().submit(()->{
			for (int i = 0; i < NUMBER_EVENTS; i++) {
				try {
					spes.publish("Test" + i);
				} catch (Exception e) {
					fail("Publish caused an unexpected exception");
				}
			}
		});
		
		assertTrue(latch.await(5, TimeUnit.SECONDS));
		assertEquals(NUMBER_EVENTS, ackCnt.intValue());
		assertEquals(0, ackErrorCnt.intValue());
		assertEquals(0, nackCnt.intValue());
	}
	
	@Test
	public void testNegativeAcknowledge() throws InterruptedException {
		AtomicInteger workCnt = new AtomicInteger();
		AtomicInteger ackCnt = new AtomicInteger();
		AtomicInteger nackCnt = new AtomicInteger();
		AtomicInteger ackErrorCnt = new AtomicInteger();
		int NUMBER_EVENTS = 5;
		CountDownLatch latch = new CountDownLatch(NUMBER_EVENTS);
		PushStreamProvider psp = new PushStreamProvider();
		AcknowledgingEventSource<String> spes = PushStreamHelper.fromClass(String.class, null);
		spes.acknowledgeFilter(s->false)
			.acknowledge(s->ackCnt.incrementAndGet())
			.negativeAcknowledge(s->{
				nackCnt.incrementAndGet();
				latch.countDown();
			})
			.acknowledgeError((t,s)->ackErrorCnt.incrementAndGet());
		
		
		PushStream<String> ps = psp.buildStream(spes).build();
		ps.onError(Throwable::printStackTrace).forEach(s->{
			workCnt.incrementAndGet();
		});
		
		Executors.newSingleThreadExecutor().submit(()->{
			for (int i = 0; i < NUMBER_EVENTS; i++) {
				try {
					spes.publish("Test" + i);
				} catch (Exception e) {
					fail("Publish caused an unexpected exception");
				}
			}
		});
		
		assertTrue(latch.await(5, TimeUnit.SECONDS));
		assertEquals(NUMBER_EVENTS, nackCnt.intValue());
		assertEquals(0, ackCnt.intValue());
		assertEquals(0, workCnt.intValue());
		assertEquals(0, ackErrorCnt.intValue());
	}
	
	@Test
	public void testFilter() throws InterruptedException {
		AtomicInteger ackFilterCnt = new AtomicInteger();
		AtomicInteger ackCnt = new AtomicInteger();
		AtomicInteger nackCnt = new AtomicInteger();
		AtomicInteger ackErrorCnt = new AtomicInteger();
		int NUMBER_EVENTS = 10;
		PushStreamProvider psp = new PushStreamProvider();
		AcknowledgingEventSource<Integer> spes = PushStreamHelper.fromClass(Integer.class, null);
		spes.acknowledgeFilter(i->{
			ackFilterCnt.incrementAndGet();
			return i % 2 == 0;
		}).negativeAcknowledge(s->nackCnt.incrementAndGet())
			.acknowledge(i->ackCnt.incrementAndGet())
			.acknowledgeError((t,s)->ackErrorCnt.incrementAndGet());;
		
		CountDownLatch latch = new CountDownLatch(NUMBER_EVENTS / 2);
		PushStream<Integer> ps = psp.buildStream(spes).build();
		ps.onError(Throwable::printStackTrace).forEach(s->{
			latch.countDown();
		});
		
		Executors.newSingleThreadExecutor().submit(()->{
			for (int i = 0; i < NUMBER_EVENTS; i++) {
				try {
					spes.publish(i);
				} catch (Exception e) {
					fail("Publish caused an unexpected exception");
				}
			}
		});
		
		assertTrue(latch.await(5, TimeUnit.SECONDS));
		assertEquals(NUMBER_EVENTS, ackFilterCnt.intValue());
		assertEquals(NUMBER_EVENTS / 2, ackCnt.intValue());
		assertEquals(NUMBER_EVENTS / 2, nackCnt.intValue());
		assertEquals(0, ackErrorCnt.intValue());
	}
	
	@Test
	public void testAckError() throws InterruptedException {
		AtomicInteger ackFilterCnt = new AtomicInteger();
		AtomicInteger ackCnt = new AtomicInteger();
		AtomicInteger ackErrorCnt = new AtomicInteger();
		int NUMBER_EVENTS = 30;
		PushStreamProvider psp = new PushStreamProvider();
		AcknowledgingEventSource<Integer> spes = PushStreamHelper.fromClass(Integer.class, null);
		spes.acknowledgeFilter(i->{
			ackFilterCnt.incrementAndGet();
			return i % 2 == 0;
		}).acknowledge(i->{
			ackCnt.incrementAndGet();
			if (i % 10 == 0) {
				throw new IllegalStateException("error " + i);
			}
		}).acknowledgeError((t,i)->ackErrorCnt.incrementAndGet());;
		
		CountDownLatch latch = new CountDownLatch(NUMBER_EVENTS / 2);
		PushStream<Integer> ps = psp.buildStream(spes).build();
		ps.onError(Throwable::printStackTrace).forEach(s->{
			latch.countDown();
		});
		
		Executors.newSingleThreadExecutor().submit(()->{
			for (int i = 0; i < NUMBER_EVENTS; i++) {
				try {
					spes.publish(i);
				} catch (Exception e) {
					fail("Publish caused an unexpected exception");
				}
			}
		});
		
		assertTrue(latch.await(5, TimeUnit.SECONDS));
		assertEquals(NUMBER_EVENTS, ackFilterCnt.intValue());
		assertEquals(NUMBER_EVENTS / 2, ackCnt.intValue());
		assertEquals(3, ackErrorCnt.intValue());
	}
	
	@Test
	public void testNegativeAckError() throws InterruptedException {
		AtomicInteger ackFilterCnt = new AtomicInteger();
		AtomicInteger ackCnt = new AtomicInteger();
		AtomicInteger nackCnt = new AtomicInteger();
		AtomicInteger ackErrorCnt = new AtomicInteger();
		int NUMBER_EVENTS = 30;
		PushStreamProvider psp = new PushStreamProvider();
		AcknowledgingEventSource<Integer> spes = PushStreamHelper.fromClass(Integer.class, null);
		spes.acknowledgeFilter(i->{
			ackFilterCnt.incrementAndGet();
			return i % 2 == 0;
		}).acknowledge(s->ackCnt.incrementAndGet()).negativeAcknowledge(i->{
			nackCnt.incrementAndGet();
			if (i % 5 == 0) {
				throw new IllegalStateException("error " + i);
			}
		}).acknowledgeError((t,i)->ackErrorCnt.incrementAndGet());;
		
		CountDownLatch latch = new CountDownLatch(NUMBER_EVENTS / 2);
		PushStream<Integer> ps = psp.buildStream(spes).build();
		ps.onError(Throwable::printStackTrace).forEach(s->{
			latch.countDown();
		});
		
		Executors.newSingleThreadExecutor().submit(()->{
			for (int i = 0; i < NUMBER_EVENTS; i++) {
				try {
					spes.publish(i);
				} catch (Exception e) {
					fail("Publish caused an unexpected exception");
				}
			}
		});
		
		assertTrue(latch.await(5, TimeUnit.SECONDS));
		assertEquals(NUMBER_EVENTS, ackFilterCnt.intValue());
		assertEquals(NUMBER_EVENTS / 2, ackCnt.intValue());
		assertEquals(NUMBER_EVENTS / 2, nackCnt.intValue());
		assertEquals(3, ackErrorCnt.intValue());
	}
	
	@Test
	public void testFilterError() throws InterruptedException {
		AtomicInteger ackFilterCnt = new AtomicInteger();
		AtomicInteger ackFilterErrorCnt = new AtomicInteger();
		AtomicInteger ackCnt = new AtomicInteger();
		AtomicInteger nackCnt = new AtomicInteger();
		AtomicInteger ackErrorCnt = new AtomicInteger();
		int NUMBER_EVENTS = 30;
		PushStreamProvider psp = new PushStreamProvider();
		AcknowledgingEventSource<Integer> spes = PushStreamHelper.fromClass(Integer.class, null);
		spes.acknowledgeFilter(i->{
			ackFilterCnt.incrementAndGet();
			if (i % 5 == 0) {
				throw new IllegalStateException("error " + i);
			}
			return i % 2 == 0;
		}).negativeAcknowledge(s->nackCnt.incrementAndGet()).acknowledge(i->ackCnt.incrementAndGet()).acknowledgeError((t,i)->ackErrorCnt.incrementAndGet());;
		
		CountDownLatch latch = new CountDownLatch(NUMBER_EVENTS / 2 - 3);
		PushStream<Integer> ps = psp.buildStream(spes).build();
		ps.onError(Throwable::printStackTrace).forEach(s->{
			latch.countDown();
		});
		
		Executors.newSingleThreadExecutor().submit(()->{
			for (int i = 0; i < NUMBER_EVENTS; i++) {
				try {
					spes.publish(i);
				} catch (Exception e) {
					ackFilterErrorCnt.incrementAndGet();
				}
			}
		});
		
		latch.await(5, TimeUnit.SECONDS);
		assertEquals(6, ackFilterErrorCnt.intValue());
		assertEquals(NUMBER_EVENTS, ackFilterCnt.intValue());
		assertEquals(NUMBER_EVENTS / 2 - 3, ackCnt.intValue());
		assertEquals(NUMBER_EVENTS / 2 - 3, nackCnt.intValue());
		assertEquals(0, ackErrorCnt.intValue());
	}

}
