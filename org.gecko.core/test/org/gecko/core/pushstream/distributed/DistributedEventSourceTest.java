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
package org.gecko.core.pushstream.distributed;


import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushEventConsumer;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamProvider;

public class DistributedEventSourceTest {

	private final PushStreamProvider psp = new PushStreamProvider();

	@BeforeEach
	public void before() {
	}

	@AfterEach
	public void after() {
	}

	@Test
	public void testExampleSimple() throws InterruptedException {
		CountDownLatch latch = new CountDownLatch(10);

		DistributedEventSource<String> streamSource = new DistributedEventSource<>(String.class);
		streamSource.onConnect(() -> {
			for(int i = 0 ; i < 10; i++) {

				streamSource.doExternalPublish("test" + i);
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		});
		PushStream<String> streamOrig = psp.createStream(streamSource);
		streamOrig
		.onClose(()->fail("Unexpected close of stream"))
		.onError(t->fail("Unexpected error of stream"))
		.forEachEvent(new PushEventConsumer<String>() {

			@Override
			public long accept(PushEvent<? extends String> event) throws Exception {
				switch (event.getType()) {
				case CLOSE:
					fail("Unexpected close event");
					break;
				case ERROR:
					fail("Unexpected error event");
					break;
				default:
					latch.countDown();
					break;
				}
				return 0;
			}
		});

		assertTrue(latch.await(5, TimeUnit.SECONDS));
	}

	@Test
	public void testExampleSimpleError() throws InterruptedException {
		CountDownLatch dataLatch = new CountDownLatch(4);
		CountDownLatch errorLatch = new CountDownLatch(1);

		DistributedEventSource<String> streamSource = new DistributedEventSource<>(String.class);
		streamSource.onConnect(() -> {
			for(int i = 0 ; i < 10; i++) {

				streamSource.doExternalPublish("test" + i);
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		});
		PushStream<String> streamOrig = psp.createStream(streamSource);

		streamOrig
			.onClose(()->System.out.println("orig stream on close"))
			.onError(t->System.err.println("orig stream error " + t.getMessage()))
			.forEachEvent(new PushEventConsumer<String>() {

				@Override
				public long accept(PushEvent<? extends String> event) throws Exception {
					switch (event.getType()) {
					case CLOSE:
						System.out.println("publish close");
						break;
					case ERROR:
						errorLatch.countDown();
						break;
					default:
						if (event.getData().endsWith("4")) {
							throw new IllegalStateException("return channel error");
						}
						dataLatch.countDown();
						break;
					}
					return 0;
				}
			});

		assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
		assertTrue(errorLatch.await(5, TimeUnit.SECONDS));
	}

	@Test
	public void testEventSourceAndStream() throws InterruptedException {

		CountDownLatch dataLatch = new CountDownLatch(10);

		DistributedEventSource<String> streamSource = new DistributedEventSource<>(String.class);
		streamSource.onConnect(() -> {
			for(int i = 0 ; i < 10; i++) {

				streamSource.doExternalPublish("test" + i);
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		});
		PushStream<String> streamOrig = streamSource.createPushStream(null);
		streamOrig
			.onClose(()->fail("unexpected stream 'on close'"))
			.onError(t->fail("unexpected stream 'on error'"))
			.forEach((s)->dataLatch.countDown());

		assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
	}

	/**
	 * Closing the push stream should call the push stream close as well as the event source close
	 * @throws InterruptedException
	 */
	@Test
	public void testSourceClosePushstream() throws InterruptedException {

		CountDownLatch closeLatch = new CountDownLatch(2);
		CountDownLatch dataLatch = new CountDownLatch(10);

		DistributedEventSource<String> streamSource = new DistributedEventSource<>(String.class);
		streamSource.onConnect(() -> {
			for(int i = 0 ; i < 10; i++) {

				streamSource.doExternalPublish("test" + i);
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		});
		streamSource.onClose(()->closeLatch.countDown());
		PushStream<String> streamOrig = streamSource.createPushStream(null);
		streamOrig
			.onClose(()->closeLatch.countDown())
			.onError(t->fail("unexpected stream 'on error'"))
			.forEach((s)->dataLatch.countDown());

		assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
		streamOrig.close();
		assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
	}

	/**
	 * External close should not trigger the event source onClose calls
	 * @throws InterruptedException
	 */
	@Test
	public void testSourceExternalClose() throws InterruptedException {

		CountDownLatch closeLatch = new CountDownLatch(1);
		CountDownLatch dataLatch = new CountDownLatch(10);

		DistributedEventSource<String> streamSource = new DistributedEventSource<>(String.class);
		streamSource.onConnect(() -> {
			for(int i = 0 ; i < 10; i++) {

				streamSource.doExternalPublish("test" + i);
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			streamSource.doExternalClose();
		});
		streamSource
			.onClose(()->fail("unexpected stream 'on close'"))
			.onError((t)->fail("unexpected stream 'on errors'"));
		PushStream<String> streamOrig = streamSource.createPushStream(null);
		streamOrig
			.onClose(()->closeLatch.countDown())
			.onError(t->fail("unexpected stream 'on error'"))
			.forEach((s)->dataLatch.countDown());

		assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
		assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
	}

	/**
	 * Triggering an external error should only trigger he push stream on error and on close
	 * @throws InterruptedException
	 */
	@Test
	public void testSourceExternalError() throws InterruptedException {

		CountDownLatch closeLatch = new CountDownLatch(1);
		CountDownLatch errorLatch = new CountDownLatch(1);
		CountDownLatch dataLatch = new CountDownLatch(4);

		DistributedEventSource<String> streamSource = new DistributedEventSource<>(String.class);
		streamSource.onConnect(() -> {
			for(int i = 0 ; i < 10; i++) {

				streamSource.doExternalPublish("test" + i);
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				if (i == 4) {
					streamSource.doExternalError(new IllegalStateException("error "));
					break;
				}
			}
		});
		streamSource
			.onClose(()->fail("unexpected stream 'on close'"))
			.onError((t)->fail("unexpected stream 'on error'"));

		PushStream<String> streamOrig = streamSource.createPushStream(null);
		streamOrig
			.onClose(()->closeLatch.countDown())
			.onError(t->errorLatch.countDown())
			.forEach((s)->dataLatch.countDown());

		assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
		assertTrue(errorLatch.await(5, TimeUnit.SECONDS));
		assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
	}

	@Test
	public void testSourceErrorPushstream01() throws InterruptedException {

		CountDownLatch closeLatch = new CountDownLatch(2);
		CountDownLatch errorLatch = new CountDownLatch(2);
		CountDownLatch dataLatch = new CountDownLatch(4);

		DistributedEventSource<String> streamSource = new DistributedEventSource<>(String.class);
		streamSource.onConnect(() -> {
			for(int i = 0 ; i < 10; i++) {

				streamSource.doExternalPublish("test" + i);
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		});
		streamSource
			.onClose(()->closeLatch.countDown())
			.onError(t->errorLatch.countDown());

		PushStream<String> streamOrig = streamSource.createPushStream(null);
		streamOrig
			.onClose(()->closeLatch.countDown())
			.onError(t->errorLatch.countDown())
			.forEach((s)->{
					dataLatch.countDown();
					if (s.endsWith("4")) {
						throw new IllegalStateException("4 error");
					}
				});


		assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
		assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
		assertTrue(errorLatch.await(5, TimeUnit.SECONDS));
	}
	
	@Test
	public void testSourceErrorPushstream02() throws InterruptedException {
		
		CountDownLatch closeLatch = new CountDownLatch(2);
		CountDownLatch errorLatch = new CountDownLatch(2);
		CountDownLatch dataLatch = new CountDownLatch(4);
		
		DistributedEventSource<String> streamSource = new DistributedEventSource<>(String.class);
		streamSource.onConnect(() -> {
			for(int i = 0 ; i < 10; i++) {
				
				streamSource.doExternalPublish("test" + i);
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		});
		streamSource
			.onClose(()->closeLatch.countDown())
			.onError(t->errorLatch.countDown());
		
		PushStream<String> streamOrig = streamSource.createPushStream(null);
		streamOrig
			.onClose(()->closeLatch.countDown())
			.onError(t->errorLatch.countDown())
			.map(s->{
				if (s.endsWith("4")) {
					throw new IllegalStateException("4 error");
				}
				return s;
			})
			.forEach((s)->{
				dataLatch.countDown();
			});
		
		assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
		assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
		assertTrue(errorLatch.await(5, TimeUnit.SECONDS));
	}
	
	@Test
	public void testSourceErrorPushstream03() throws InterruptedException {
		
		CountDownLatch closeLatch = new CountDownLatch(2);
		CountDownLatch errorLatch = new CountDownLatch(2);
		CountDownLatch dataLatch = new CountDownLatch(4);
		
		DistributedEventSource<String> streamSource = new DistributedEventSource<>(String.class);
		streamSource.onConnect(() -> {
			for(int i = 0 ; i < 10; i++) {
				
				streamSource.doExternalPublish("test" + i);
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		});
		streamSource
			.onClose(()->closeLatch.countDown())
			.onError(t->errorLatch.countDown());
		
		PushStream<String> streamOrig = streamSource.createPushStream(null);
		streamOrig
			.map(s->{
				if (s.endsWith("4")) {
					throw new IllegalStateException("4 error");
				}
				return s;
			})
			.onClose(()->closeLatch.countDown())
			.onError(t->errorLatch.countDown())
			.forEach((s)->{
				dataLatch.countDown();
			});
		
		assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
		assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
		assertTrue(errorLatch.await(5, TimeUnit.SECONDS));
	}

}