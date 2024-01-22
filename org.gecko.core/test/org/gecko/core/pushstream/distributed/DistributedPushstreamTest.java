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
import org.osgi.util.pushstream.PushEventSource;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamProvider;
import org.osgi.util.pushstream.SimplePushEventSource;

public class DistributedPushstreamTest {

	private final PushStreamProvider psp = new PushStreamProvider();

	@BeforeEach
	public void before() {
	}

	@AfterEach
	public void after() {
	}

	@Test
	public void testSimpleDistPushStream() throws InterruptedException {
		
		CountDownLatch dataLatch = new CountDownLatch(10);
		
		PushStream<String> streamOrig = getMyFancyStream();
		streamOrig
			.onClose(()->fail("unexpected stream 'on close'"))
			.onError(t->fail("unexpected stream 'on error'"));
		
		// Attach a distributes consumer to any push stream
		DistributedConsumer<String> distConsumer = new DistributedConsumer<String>(streamOrig);
		
		// Create a distributed event source that represents the client source
		DistributedEventSource<String> distSource = new DistributedEventSource<>(String.class);
		
		/*
		 * If the client source is connected, we also connect the server stream.
		 * This is the return channel from the client to the servant stream.
		 * Beside the connect information we also delegate errors and close events
		 * from the client stream up to the servant stream
		 */
		distSource.onConnect(distConsumer::doConnect);
		distSource.onError(distConsumer::doExternalError);
		distSource.onClose(distConsumer::doExternalClose);
		
		/* 
		 * We connect the consumer accept data and publish it to the client source.
		 * the same applies to the error and close handling.
		 */
		distConsumer.onAccept(distSource::doExternalPublish);
		distConsumer.onError(distSource::doExternalError);
		distConsumer.onClose(distSource::doExternalClose);
		
		
		PushStream<String> clientStream = distSource.createPushStream(null);
		clientStream
			.onClose(()->fail("unexpected stream 'on close'"))
			.onError(t->fail("unexpected stream 'on error'"))
			.forEach((s)->dataLatch.countDown());
		
		assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
		
	}
	
	/**
	 * Errors in the servant stream are propagated to its error callbacks.
	 * In addition to that they are also delegated to the client pushstream
	 * @throws InterruptedException
	 */
	@Test
	public void testDistPushStreamServantError() throws InterruptedException {
		
		CountDownLatch dataLatch = new CountDownLatch(4);
		CountDownLatch errorLatch = new CountDownLatch(2);
		CountDownLatch closeLatch = new CountDownLatch(2);
		
		PushStream<String> streamOrig = getMyFancyStream();
		streamOrig = streamOrig
			.onClose(()->closeLatch.countDown())
			.onError(t->errorLatch.countDown())
			.map(s-> {
				if (s.endsWith("4")) {
					throw new IllegalStateException("servant error");
				}
				return s;
			});
		
		// Attach a distributes consumer to any push stream
		DistributedConsumer<String> distConsumer = new DistributedConsumer<String>(streamOrig);
		
		// Create a distributed event source that represents the client source
		DistributedEventSource<String> distSource = new DistributedEventSource<>(String.class);
		
		/*
		 * If the client source is connected, we also connect the server stream.
		 * This is the return channel from the client to the servant stream.
		 * Beside the connect information we also delegate errors and close events
		 * from the client stream up to the servant stream
		 */
		distSource.onConnect(distConsumer::doConnect);
		distSource.onError(distConsumer::doExternalError);
		distSource.onClose(distConsumer::doExternalClose);
		
		/* 
		 * We connect the consumer accept data and publish it to the client source.
		 * the same applies to the error and close handling.
		 */
		distConsumer.onAccept(distSource::doExternalPublish);
		distConsumer.onError(distSource::doExternalError);
		distConsumer.onClose(distSource::doExternalClose);
		
		
		PushStream<String> clientStream = distSource.createPushStream(null);
		clientStream
			.onClose(()->closeLatch.countDown())
			.onError(t->errorLatch.countDown())
			.forEach((s)->dataLatch.countDown());
		
		assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
		assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
		assertTrue(errorLatch.await(5, TimeUnit.SECONDS));
	}
	
	/**
	 * Errors in the client stream are not delegated back to the remote sevant. But the close will be delegated
	 * @throws InterruptedException
	 */
	@Test
	public void testDistPushStreamClientError() throws InterruptedException {
		
		CountDownLatch dataLatch = new CountDownLatch(4);
		CountDownLatch errorLatch = new CountDownLatch(1);
		CountDownLatch closeLatch = new CountDownLatch(2);
		
		PushStream<String> streamOrig = getMyFancyStream();
		streamOrig
				.onClose(()->closeLatch.countDown())
				.onError(t->fail("unexpected servant 'on error"));
				
		
		// Attach a distributes consumer to any push stream
		DistributedConsumer<String> distConsumer = new DistributedConsumer<String>(streamOrig);
		
		// Create a distributed event source that represents the client source
		DistributedEventSource<String> distSource = new DistributedEventSource<>(String.class);
		
		/*
		 * If the client source is connected, we also connect the server stream.
		 * This is the return channel from the client to the servant stream.
		 * Beside the connect information we also delegate errors and close events
		 * from the client stream up to the servant stream
		 */
		distSource.onConnect(distConsumer::doConnect);
		distSource.onError(distConsumer::doExternalError);
		distSource.onClose(distConsumer::doExternalClose);
		
		/* 
		 * We connect the consumer accept data and publish it to the client source.
		 * the same applies to the error and close handling.
		 */
		distConsumer.onAccept(distSource::doExternalPublish);
		distConsumer.onError(distSource::doExternalError);
		distConsumer.onClose(distSource::doExternalClose);
		
		
		PushStream<String> clientStream = distSource.createPushStream(null);
		clientStream
			.onClose(()->closeLatch.countDown())
			.onError(t->errorLatch.countDown())
			.map(s-> {
				if (s.endsWith("4")) {
					throw new IllegalStateException("servant error");
				}
				return s;
			})
			.forEach((s)->dataLatch.countDown());
		
		assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
		assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
		assertTrue(errorLatch.await(5, TimeUnit.SECONDS));
	}
	
	/**
	 * Errors in the servant stream are propagated to its error callbacks.
	 * In addition to that they are also delegated to the client pushstream
	 * @throws InterruptedException
	 */
	@Test
	public void testDistPushStreamServantClose() throws InterruptedException {
		
		CountDownLatch dataLatch = new CountDownLatch(4);
		CountDownLatch closeLatch = new CountDownLatch(2);
		
		final PushStream<String> streamOrig = getMyFancyStream();
		final PushStream<String> streamMap = streamOrig
				.onClose(()->closeLatch.countDown())
				.onError(t->fail("unexpected servant 'on error'"))
				.map(s-> {
					if (s.endsWith("4")) {
						streamOrig.close();
					}
					return s;
				});
		
		// Attach a distributes consumer to any push stream
		DistributedConsumer<String> distConsumer = new DistributedConsumer<String>(streamMap);
		
		// Create a distributed event source that represents the client source
		DistributedEventSource<String> distSource = new DistributedEventSource<>(String.class);
		
		/*
		 * If the client source is connected, we also connect the server stream.
		 * This is the return channel from the client to the servant stream.
		 * Beside the connect information we also delegate errors and close events
		 * from the client stream up to the servant stream
		 */
		distSource.onConnect(distConsumer::doConnect);
		distSource.onError(distConsumer::doExternalError);
		distSource.onClose(distConsumer::doExternalClose);
		
		/* 
		 * We connect the consumer accept data and publish it to the client source.
		 * the same applies to the error and close handling.
		 */
		distConsumer.onAccept(distSource::doExternalPublish);
		distConsumer.onError(distSource::doExternalError);
		distConsumer.onClose(distSource::doExternalClose);
		
		
		PushStream<String> clientStream = distSource.createPushStream(null);
		clientStream
			.onClose(()->closeLatch.countDown())
			.onError(t->fail("unexpected client 'on error'"))
			.forEach((s)->dataLatch.countDown());
		
		assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
		assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
	}
	
	/**
	 * Errors in the client stream are not delegated back to the remote sevant. But the close will be delegated
	 * @throws InterruptedException
	 */
	@Test
	public void testDistPushStreamClientClose() throws InterruptedException {
		
		CountDownLatch dataLatch = new CountDownLatch(4);
		CountDownLatch closeLatch = new CountDownLatch(2);
		
		final PushStream<String> streamOrig = getMyFancyStream();
		final PushStream<String> streamMap = streamOrig
				.onClose(()->closeLatch.countDown())
				.onError(t->fail("unexpected servant 'on error'"));
		
		// Attach a distributes consumer to any push stream
		DistributedConsumer<String> distConsumer = new DistributedConsumer<String>(streamMap);
		
		// Create a distributed event source that represents the client source
		DistributedEventSource<String> distSource = new DistributedEventSource<>(String.class);
		
		/*
		 * If the client source is connected, we also connect the server stream.
		 * This is the return channel from the client to the servant stream.
		 * Beside the connect information we also delegate errors and close events
		 * from the client stream up to the servant stream
		 */
		distSource.onConnect(distConsumer::doConnect);
		distSource.onError(distConsumer::doExternalError);
		distSource.onClose(distConsumer::doExternalClose);
		
		/* 
		 * We connect the consumer accept data and publish it to the client source.
		 * the same applies to the error and close handling.
		 */
		distConsumer.onAccept(distSource::doExternalPublish);
		distConsumer.onError(distSource::doExternalError);
		distConsumer.onClose(distSource::doExternalClose);
		
		
		PushStream<String> clientStream = distSource.createPushStream(null);
		clientStream
			.onClose(()->closeLatch.countDown())
			.onError(t->fail("unexpected client 'on error'"))
			.map(s-> {
				if (s.endsWith("4")) {
					streamOrig.close();
				}
				return s;
			})
			.forEach((s)->dataLatch.countDown());
		
		assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
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

}