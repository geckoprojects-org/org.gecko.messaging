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
import org.osgi.util.pushstream.PushEventSource;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamProvider;
import org.osgi.util.pushstream.SimplePushEventSource;

public class DistributedConsumerTest {

	private final PushStreamProvider psp = new PushStreamProvider();

	@BeforeEach
	public void before() {
	}

	@AfterEach
	public void after() {
	}

	@org.junit.jupiter.api.Test
	public void testExampleSimple() throws InterruptedException {
		CountDownLatch latch = new CountDownLatch(10);
		
		PushEventSource<String> streamSource = getMyFancySource();
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
					System.out.println("publish error " + event.getFailure().getMessage());
					break;
				default:
					System.out.println("publish data: " + event.getData());
					latch.countDown();
					break;
				}
				return 0;
			}
		});
		
		assertTrue(latch.await(5, TimeUnit.SECONDS));
		// forEachEvent -> serialize to remote PES
	}
	
	@Test
	public void testExampleSimpleError() throws InterruptedException {
		CountDownLatch dataLatch = new CountDownLatch(4);
		CountDownLatch errorLatch = new CountDownLatch(1);
		
		PushEventSource<String> streamSource = getMyFancySource();
		PushStream<String> streamOrig = psp.createStream(streamSource);
		
		streamOrig
			.onClose(()->System.out.println("orig stream on close"))
			.onError(t->System.err.println("orig stream error " + t.getMessage()))
			.forEachEvent(new PushEventConsumer<String>() {
			
				@Override
				public long accept(PushEvent<? extends String> event) throws Exception {
					switch (event.getType()) {
					case CLOSE:
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
		// forEachEvent -> serialize to remote PES
	}
	
	@Test
	public void testConsumer() throws InterruptedException {
		
		CountDownLatch dataLatch = new CountDownLatch(10);
		
		PushStream<String> streamOrig = getMyFancyStream();
		streamOrig
		.onClose(()->fail("unexpected stream 'on close'"))
		.onError(t->fail("unexpected stream 'on error'"));
		
		DistributedConsumer<String> consumer = new DistributedConsumer<String>(streamOrig);
		consumer
		.onClose(()->fail("unexpected consumer 'on close'"))
		.onError(t->fail("unexpected consumer 'on error'"))
		.onAccept(s->{
			dataLatch.countDown();
		});
		
		consumer.doConnect();
		
		assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
	}
	
	@Test
	public void testConsumerInternalClose() throws InterruptedException {
		
		CountDownLatch closeLatch = new CountDownLatch(2);
		CountDownLatch dataLatch = new CountDownLatch(10);
		
		PushStream<String> streamOrig = getMyFancyStream();
		streamOrig
			.onClose(()->closeLatch.countDown())
			.onError(t->fail("unexpected stream 'on error'"));
		
		DistributedConsumer<String> consumer = new DistributedConsumer<String>(streamOrig);
		consumer
			.onClose(()->closeLatch.countDown())
			.onError(t->fail("unexpected consumer 'on error'"))
			.onAccept(s->{
				dataLatch.countDown();
			});
		
		consumer.doConnect();
		
		assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
		streamOrig.close();
		assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
	}
	
	@Test
	public void testConsumerExternalClose() throws InterruptedException {
		
		CountDownLatch closeLatch = new CountDownLatch(1);
		CountDownLatch dataLatch = new CountDownLatch(5);
		
		PushStream<String> streamOrig = getMyFancyStream();
		streamOrig
		.onClose(()->closeLatch.countDown())
		.onError(t->fail("unexpected stream 'on error'"));
		
		DistributedConsumer<String> consumer = new DistributedConsumer<String>(streamOrig);
		consumer
		.onClose(()->fail("unexpected consumer 'on close'"))
		.onError(t->fail("unexpected consumer 'on error'"))
		.onAccept(s->{
			if (s.endsWith("5")) {
				consumer.doExternalClose();
			}
			dataLatch.countDown();
		});
		
		consumer.doConnect();
		
		assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
		assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
	}
	
	@Test
	public void testConsumerExternalError() throws InterruptedException {
		
		CountDownLatch closeLatch = new CountDownLatch(1);
		CountDownLatch errorLatch = new CountDownLatch(1);
		CountDownLatch dataLatch = new CountDownLatch(4);
		
		PushStream<String> streamOrig = getMyFancyStream();
		
		streamOrig
			.onClose(()->closeLatch.countDown())
			.onError(t->errorLatch.countDown());
		
		DistributedConsumer<String> consumer = new DistributedConsumer<String>(streamOrig);
		consumer
			.onClose(()->fail("unexpected consumer 'on close'"))
			.onError(t->fail("unexpected consumer 'on error'"))
			.onAccept(s->{
				if (s.endsWith("4")) {
					consumer.doExternalError(new IllegalStateException("error "));
				}
				dataLatch.countDown();
			});
		
		consumer.doConnect();
		
		assertTrue(dataLatch.await(15, TimeUnit.SECONDS));
		assertTrue(errorLatch.await(15, TimeUnit.SECONDS));
		assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
	}
	
	@Test
	public void testConsumerInternalError() throws InterruptedException {
		
		CountDownLatch closeLatch = new CountDownLatch(1);
		CountDownLatch errorLatch = new CountDownLatch(2);
		CountDownLatch dataLatch = new CountDownLatch(4);
		
		PushStream<String> streamOrig = getMyFancyStream();
		
		streamOrig = streamOrig.map(s->{
			if (s.endsWith("4")) {
				throw new IllegalStateException("4 error");
			}
			return s;
		})
		.onClose(()->closeLatch.countDown())
		.onError(t->errorLatch.countDown());
		
		DistributedConsumer<String> consumer = new DistributedConsumer<String>(streamOrig);
		consumer
		.onClose(()->fail("publish 'on close'"))
		.onError(t->errorLatch.countDown())
		.onAccept(s->{
			dataLatch.countDown();
		});
		
		consumer.doConnect();
		
		assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
		assertTrue(errorLatch.await(5, TimeUnit.SECONDS));
		streamOrig.close();
		assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
	}
	
	PushStream<String> getMyFancyStream(){
		PushEventSource<String> source = getMyFancySource();
		PushStream<String> stream = psp.createStream(source);
		return stream;
	}
	
	PushEventSource<String> getMyFancySource(){
		PushStreamProvider psp = new PushStreamProvider();
		SimplePushEventSource<String> source = psp.createSimpleEventSource(String.class);
		source.connectPromise().onResolve(() -> {
			for(int i = 0 ; i < 10; i++) {
				
				source.publish("test" + i);
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		});
		return source;
	}
	
	PushEventSource<String> getMyFancySourceFromStream(PushStream<String> source){
		PushStreamProvider psp = new PushStreamProvider();
		return psp.createEventSourceFromStream(source);
	}

}