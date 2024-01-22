/**
 * Copyright (c) 2012 - 2019 Data In Motion and others.
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

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;

import org.gecko.core.pushstream.policy.FixedGradePushbackPolicy;
import org.gecko.core.pushstream.policy.GradualBreakingQueuePolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.util.promise.PromiseFactory;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamProvider;
import org.osgi.util.pushstream.PushbackPolicyOption;
import org.osgi.util.pushstream.QueuePolicyOption;
import org.osgi.util.pushstream.SimplePushEventSource;

/**
 * 
 * @author mark
 * @since 26.01.2019
 */
public class PushstreamPerformanceTest {

	private AtomicLong counter = new AtomicLong();
	private AtomicLong requestTime = new AtomicLong();
	private AtomicLong workerTime = new AtomicLong();
	private int messages = 500;
	private CountDownLatch cdl;
	private MBeanServer mbs;
	private Map<String, Long> timeMap;

	@BeforeEach
	public void setup() {
		counter.set(0);
		requestTime.set(0);
		workerTime.set(0);
		timeMap = new ConcurrentHashMap<String, Long>(3000);
		mbs = ManagementFactory.getPlatformMBeanServer();
	}
	
	@AfterEach
	public void tearDown() {
		timeMap.clear();
	}

	// @Test
	public void testSimple01() throws InterruptedException {
		PushStreamProvider psp = new PushStreamProvider();
		PromiseFactory pf = new PromiseFactory(Executors.newSingleThreadExecutor());
		SimplePushEventSource<String> spes = psp.createSimpleEventSource(String.class);

		pf.submit(() -> {
			boolean active = true;
			while (active) {
				if (spes.isConnected() && counter.get() < messages) {
					String message = generateMessage(10, 500);
					spes.publish(message);
					// System.out.println(System.currentTimeMillis() + " pub " + message);
				}
			}
			return spes;
		});

		cdl = new CountDownLatch(messages);
		long time = System.currentTimeMillis();
		PushStream<String> ps1 = psp.createStream(spes);
		ps1.onError(t -> System.err.println("Pushstream error: " + t.getMessage())).forEach(this::consumeMessage);

		assertTrue(cdl.await(360, TimeUnit.SECONDS));
		System.out.println("Simple test took " + (System.currentTimeMillis() - time) + "ms request time is: "
				+ requestTime.get() + ", worker time is: " + workerTime.get());

	}

	// @Test
	public void testOptimized01() throws InterruptedException {
		
		PushStreamProvider psp = new PushStreamProvider();
		PromiseFactory pf = new PromiseFactory(Executors.newSingleThreadExecutor());
		SimplePushEventSource<String> spes = psp.createSimpleEventSource(String.class);

		pf.submit(() -> {
			boolean active = true;
			while (active) {
				if (spes.isConnected() && counter.get() < messages) {
					String message = generateMessage(10, 100);
					spes.publish(message);
					// System.out.println(System.currentTimeMillis() + " pub " + message);
				}
			}
			return spes;
		});

		cdl = new CountDownLatch(messages);
		long time = System.currentTimeMillis();
		PushStream<String> ps1 = psp.buildStream(spes).withExecutor(Executors.newCachedThreadPool()).withParallelism(4)
				.withPushbackPolicy(PushbackPolicyOption.FIXED, 10).withQueuePolicy(QueuePolicyOption.BLOCK)
				.withBuffer(new ArrayBlockingQueue<PushEvent<? extends String>>(100)).build();
		ps1.onError(t -> System.err.println("Pushstream error: " + t.getMessage())).forEach(this::consumeMessage);

		assertTrue(cdl.await(360, TimeUnit.SECONDS));
		System.out.println("MultiThreaded 4x buffer 100 test took " + (System.currentTimeMillis() - time)
				+ "ms request time is: " + requestTime.get() + ", worker time is: " + workerTime.get());

	}

	// @Test
	public void testOptimized03() throws InterruptedException {
		PushStreamProvider psp = new PushStreamProvider();
		PromiseFactory pf = new PromiseFactory(Executors.newSingleThreadExecutor());
		SimplePushEventSource<String> spes = psp.createSimpleEventSource(String.class);

		pf.submit(() -> {
			boolean active = true;
			while (active) {
				if (spes.isConnected() && counter.get() < messages) {
					String message = generateMessage(10, 100);
					spes.publish(message);
					// System.out.println(System.currentTimeMillis() + " pub " + message);
				}
			}
			return spes;
		});

		cdl = new CountDownLatch(messages);
		long time = System.currentTimeMillis();
		PushStream<String> ps1 = psp.buildStream(spes).withExecutor(Executors.newCachedThreadPool()).withParallelism(4)
				.withPushbackPolicy(PushbackPolicyOption.FIXED, 10).withQueuePolicy(QueuePolicyOption.BLOCK)
				.withBuffer(new ArrayBlockingQueue<PushEvent<? extends String>>(200)).build();
		ps1.onError(t -> System.err.println("Pushstream error: " + t.getMessage())).forEach(this::consumeMessage);

		assertTrue(cdl.await(180, TimeUnit.SECONDS));
		System.out.println("MultiThreaded 4x buffer 200 test took " + (System.currentTimeMillis() - time)
				+ "ms request time is: " + requestTime.get() + ", worker time is: " + workerTime.get());

	}

	// @Test
	public void testOptimized04() throws InterruptedException {
		PushStreamProvider psp = new PushStreamProvider();
		PromiseFactory pf = new PromiseFactory(Executors.newSingleThreadExecutor());
		SimplePushEventSource<String> spes = psp.createSimpleEventSource(String.class);

		pf.submit(() -> {
			boolean active = true;
			while (active) {
				if (spes.isConnected() && counter.get() < messages) {
					String message = generateMessage(10, 100);
					spes.publish(message);
					// System.out.println(System.currentTimeMillis() + " pub " + message);
				}
			}
			return spes;
		});

		cdl = new CountDownLatch(messages);
		long time = System.currentTimeMillis();
		PushStream<String> ps1 = psp.buildStream(spes).withExecutor(Executors.newCachedThreadPool()).withParallelism(8)
				.withPushbackPolicy(PushbackPolicyOption.FIXED, 10).withQueuePolicy(QueuePolicyOption.BLOCK)
				.withBuffer(new ArrayBlockingQueue<PushEvent<? extends String>>(200)).build();
		ps1.onError(t -> System.err.println("Pushstream error: " + t.getMessage())).forEach(this::consumeMessage);

		assertTrue(cdl.await(180, TimeUnit.SECONDS));
		System.out.println("MultiThreaded 8x buffer 200 test took " + (System.currentTimeMillis() - time)
				+ "ms request time is: " + requestTime.get() + ", worker time is: " + workerTime.get());

	}

	// @Test
	public void testOptimized05() throws InterruptedException {
		PushStreamProvider psp = new PushStreamProvider();
		PromiseFactory pf = new PromiseFactory(Executors.newSingleThreadExecutor());
		SimplePushEventSource<String> spes = psp.createSimpleEventSource(String.class);

		pf.submit(() -> {
			boolean active = true;
			while (active) {
				if (spes.isConnected() && counter.get() < messages) {
					String message = generateMessage(10, 100);
					spes.publish(message);
					// System.out.println(System.currentTimeMillis() + " pub " + message);
				}
			}
			return spes;
		});

		cdl = new CountDownLatch(messages);
		long time = System.currentTimeMillis();
		PushStream<String> ps1 = psp.buildStream(spes).withExecutor(Executors.newCachedThreadPool()).withParallelism(16)
				.withPushbackPolicy(PushbackPolicyOption.FIXED, 10).withQueuePolicy(QueuePolicyOption.BLOCK)
				.withBuffer(new ArrayBlockingQueue<PushEvent<? extends String>>(100)).build();
		ps1.onError(t -> System.err.println("Pushstream error: " + t.getMessage())).forEach(this::consumeMessage);

		assertTrue(cdl.await(180, TimeUnit.SECONDS));
		System.out.println("MultiThreaded 16x buffer 100 test took " + (System.currentTimeMillis() - time)
				+ "ms request time is: " + requestTime.get() + ", worker time is: " + workerTime.get());

	}

	// @Test
	public void testOptimized06() throws InterruptedException {
		PushStreamProvider psp = new PushStreamProvider();
		PromiseFactory pf = new PromiseFactory(Executors.newSingleThreadExecutor());
		SimplePushEventSource<String> spes = psp.createSimpleEventSource(String.class);

		pf.submit(() -> {
			boolean active = true;
			while (active) {
				if (spes.isConnected() && counter.get() < messages) {
					String message = generateMessage(10, 100);
					spes.publish(message);
					// System.out.println(System.currentTimeMillis() + " pub " + message);
				}
			}
			return spes;
		});

		cdl = new CountDownLatch(messages);
		long time = System.currentTimeMillis();
		PushStream<String> ps1 = psp.buildStream(spes).withExecutor(Executors.newCachedThreadPool()).withParallelism(16)
				.withPushbackPolicy(PushbackPolicyOption.FIXED, 10).withQueuePolicy(QueuePolicyOption.BLOCK)
				.withBuffer(new ArrayBlockingQueue<PushEvent<? extends String>>(200)).build();
		ps1.onError(t -> System.err.println("Pushstream error: " + t.getMessage())).forEach(this::consumeMessage);

		assertTrue(cdl.await(180, TimeUnit.SECONDS));
		System.out.println("MultiThreaded 16x buffer 200 test took " + (System.currentTimeMillis() - time)
				+ "ms request time is: " + requestTime.get() + ", worker time is: " + workerTime.get());

	}

//	@Test
	public void testOptimized07() throws InterruptedException {
		PushStreamProvider psp = new PushStreamProvider();
		PromiseFactory pf = new PromiseFactory(Executors.newSingleThreadExecutor());
		SimplePushEventSource<String> spes = psp.createSimpleEventSource(String.class);

		pf.submit(() -> {
			boolean active = true;
			while (active) {
				if (spes.isConnected() && counter.get() < messages) {
					String message = generateMessage(10, 100);
					spes.publish(message);
					// System.out.println(System.currentTimeMillis() + " pub " + message);
				}
			}
			return spes;
		});

		cdl = new CountDownLatch(messages);
		long time = System.currentTimeMillis();
		PushStream<String> ps1 = psp.buildStream(spes).withExecutor(Executors.newCachedThreadPool()).withParallelism(8)
				.withPushbackPolicy(PushbackPolicyOption.LINEAR, 1).withQueuePolicy(QueuePolicyOption.BLOCK)
				.withBuffer(new ArrayBlockingQueue<PushEvent<? extends String>>(100)).build();
		ps1.onError(t -> System.err.println("Pushstream error: " + t.getMessage())).forEach(this::consumeMessage);

		assertTrue(cdl.await(360, TimeUnit.SECONDS));
		System.out.println("MultiThreaded 8x buffer 100 linear 1 test took " + (System.currentTimeMillis() - time)
				+ "ms request time is: " + requestTime.get() + ", worker time is: " + workerTime.get());

	}

//	@Test
	public void testOptimized08() throws InterruptedException {
		PushStreamProvider psp = new PushStreamProvider();
		PromiseFactory pf = new PromiseFactory(Executors.newSingleThreadExecutor());
		SimplePushEventSource<String> spes = psp.createSimpleEventSource(String.class);

		pf.submit(() -> {
			boolean active = true;
			while (active) {
				if (spes.isConnected() && counter.get() < messages) {
					String message = generateMessage(10, 100);
					spes.publish(message);
					// System.out.println(System.currentTimeMillis() + " pub " + message);
				}
			}
			return spes;
		});

		cdl = new CountDownLatch(messages);
		long time = System.currentTimeMillis();
		PushStream<String> ps1 = psp.buildStream(spes).withExecutor(Executors.newCachedThreadPool()).withParallelism(8)
				.withPushbackPolicy(PushbackPolicyOption.ON_FULL_EXPONENTIAL, 2)
				.withQueuePolicy(QueuePolicyOption.BLOCK)
				.withBuffer(new ArrayBlockingQueue<PushEvent<? extends String>>(100)).build();
		ps1.onError(t -> System.err.println("Pushstream error: " + t.getMessage()))
		.fork(4, 2, Executors.newCachedThreadPool()).forEach(this::consumeMessage);

		assertTrue(cdl.await(360, TimeUnit.SECONDS));
		System.out.println("MultiThreaded 8x buffer 100 exp 2 test took " + (System.currentTimeMillis() - time)
				+ "ms request time is: " + requestTime.get() + ", worker time is: " + workerTime.get());

	}

	@Test
	public void test2StreamsOptimized() throws InterruptedException {
		PushStreamProvider psp = new PushStreamProvider();
		PromiseFactory pf = new PromiseFactory(Executors.newSingleThreadExecutor());
		SimplePushEventSource<String> spes = psp.createSimpleEventSource(String.class);

		pf.submit(() -> {
			boolean active = true;
			while (active) {
				if (spes.isConnected() && counter.get() < messages) {
					String message = generateMessage(10, 50);
					spes.publish(message);
				}
			}
			return spes;
		});
		AtomicInteger error = new AtomicInteger();
		cdl = new CountDownLatch(messages * 2);
		long time = System.currentTimeMillis();
		PushStream<String> ps1 = psp.buildStream(spes).withExecutor(Executors.newCachedThreadPool()).withParallelism(8)
				.withPushbackPolicy(PushbackPolicyOption.FIXED, 10)
				.withQueuePolicy(QueuePolicyOption.BLOCK)
				.withBuffer(new ArrayBlockingQueue<PushEvent<? extends String>>(200)).build();
		ps1.onError(t -> {
			System.err.println("Pushstream1 error: " + t.getMessage());
			error.incrementAndGet();
		}).forEach(this::consumeMessage);
		PushStream<String> ps2 = psp.buildStream(spes).withExecutor(Executors.newCachedThreadPool()).withParallelism(8)
				.withPushbackPolicy(PushbackPolicyOption.ON_FULL_EXPONENTIAL, 10)
				.withQueuePolicy(QueuePolicyOption.BLOCK)
				.withBuffer(new ArrayBlockingQueue<PushEvent<? extends String>>(500)).build();
		ps2.onError(t -> {
			System.err.println("Pushstream2 error: " + t.getMessage());
			error.incrementAndGet();
		}).forEach(this::consumeMessage);

		assertTrue(cdl.await(9, TimeUnit.HOURS));
		assertEquals(0, error.get());
		System.out.println("Default test took " + (System.currentTimeMillis() - time) + "ms request time is: "
				+ requestTime.get() + ", worker time is: " + workerTime.get());

	}

	@Test
	public void test2StreamsOptimizedMeasure() throws InterruptedException, InstanceAlreadyExistsException, MBeanRegistrationException, NotCompliantMBeanException {
		final int bufferSize = 100;
		
		FixedGradePushbackPolicy<String, BlockingQueue<PushEvent<? extends String>>> streamPolicy = new FixedGradePushbackPolicy<String, BlockingQueue<PushEvent<? extends String>>>("stream", 80, bufferSize, 5);
		GradualBreakingQueuePolicy<String, BlockingQueue<PushEvent<? extends String>>> sourcePolicy = new GradualBreakingQueuePolicy<String, BlockingQueue<PushEvent<? extends String>>>("source", 80, bufferSize, 2);
		try {
			ObjectName streamName = new ObjectName("Pushstream:name=StreamPolicy");
			mbs.registerMBean(streamPolicy.getMBean(), streamName);

			ObjectName sourceName = new ObjectName("Pushstream:name=SourcePolicy");
			mbs.registerMBean(sourcePolicy.getMBean(), sourceName);
		} catch (MalformedObjectNameException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		PushStreamProvider psp = new PushStreamProvider();
		PromiseFactory pf = new PromiseFactory(Executors.newSingleThreadExecutor());
		SimplePushEventSource<String> spes = psp.buildSimpleEventSource(String.class)
				.withBuffer(new ArrayBlockingQueue<PushEvent<? extends String>>(bufferSize))
				.withQueuePolicy(sourcePolicy)
				.build();

		pf.submit(() -> {
			boolean active = true;
			while (active) {
				if (spes.isConnected() && counter.get() < messages) {
					String message = generateMessage(5, 50);
					spes.publish(message);
				}
			}
			return spes;
		});
		AtomicInteger error = new AtomicInteger();
		cdl = new CountDownLatch(messages);
		long time = System.currentTimeMillis();
//		PushStream<String> ps1 = psp.buildStream(spes).withExecutor(Executors.newCachedThreadPool()).withParallelism(8)
//				.withPushbackPolicy(PushbackPolicyOption.FIXED, 10)
//				.withQueuePolicy(QueuePolicyOption.BLOCK)
//				.withBuffer(new ArrayBlockingQueue<PushEvent<? extends String>>(200)).build();
//		ps1.onError(t -> {
//			System.err.println("Pushstream1 error: " + t.getMessage());
//			error.incrementAndGet();
//		}).forEach(this::consumeMessage);
		PushStream<String> ps2 = psp.buildStream(spes).withExecutor(Executors.newCachedThreadPool()).withParallelism(8)
				.withPushbackPolicy(streamPolicy)
//				.withPushbackPolicy(PushbackPolicyOption.FIXED, 10)
				.withQueuePolicy(QueuePolicyOption.BLOCK)
				.withBuffer(new ArrayBlockingQueue<PushEvent<? extends String>>(bufferSize)).build();
		ps2.onError(t -> {
			System.err.println("Pushstream2 error: " + t.getMessage());
			t.printStackTrace();
			error.incrementAndGet();
		}).forEach(this::consumeMessage);

		assertTrue(cdl.await(10, TimeUnit.MINUTES));
		assertEquals(0, error.get());
		System.out.println("Default test took " + (System.currentTimeMillis() - time) + "ms request time is: "
				+ requestTime.get() + ", worker time is: " + workerTime.get());

	}

	@Test
	public void testOptimized09() throws InterruptedException {
		PushStreamProvider psp = new PushStreamProvider();
		PromiseFactory pf = new PromiseFactory(Executors.newSingleThreadExecutor());
		SimplePushEventSource<String> spes = psp.createSimpleEventSource(String.class);

		pf.submit(() -> {
			boolean active = true;
			while (active) {
				if (spes.isConnected() && counter.get() < messages) {
					String message = generateMessage(10, 100);
					spes.publish(message);
					// System.out.println(System.currentTimeMillis() + " pub " + message);
				}
			}
			return spes;
		});

		cdl = new CountDownLatch(messages);
		long time = System.currentTimeMillis();
		PushStream<String> ps1 = psp.buildStream(spes).withExecutor(Executors.newCachedThreadPool()).withParallelism(8)
				.withPushbackPolicy(PushbackPolicyOption.ON_FULL_EXPONENTIAL, 2)
				.withQueuePolicy(QueuePolicyOption.BLOCK)
				.withBuffer(new ArrayBlockingQueue<PushEvent<? extends String>>(100)).build();
		ps1.onError(t -> System.err.println("Pushstream error: " + t.getMessage())).forEach(this::consumeMessage);

		assertTrue(cdl.await(360, TimeUnit.SECONDS));
		System.out
		.println("MultiThreaded 8x buffer 100 fixed on full 10 test took " + (System.currentTimeMillis() - time)
				+ "ms request time is: " + requestTime.get() + ", worker time is: " + workerTime.get());

	}

	// @Test
	public void testSimple02() throws InterruptedException {
		PushStreamProvider psp = new PushStreamProvider();
		PromiseFactory pf = new PromiseFactory(Executors.newSingleThreadExecutor());
		SimplePushEventSource<String> spes = psp.createSimpleEventSource(String.class);

		pf.submit(() -> {
			boolean active = true;
			while (active) {
				if (spes.isConnected() && counter.get() < messages) {
					String message = generateMessage(10, 500);
					spes.publish(message);
					// System.out.println(System.currentTimeMillis() + " pub " + message);
				}
			}
			return spes;
		});

		cdl = new CountDownLatch(messages);
		long time = System.currentTimeMillis();
		PushStream<String> ps1 = psp.createStream(spes);
		ps1.onError(t -> System.err.println("Pushstream error: " + t.getMessage())).forEach(this::consumeMessage);

		assertTrue(cdl.await(180, TimeUnit.SECONDS));
		System.out.println("Simple test took " + (System.currentTimeMillis() - time) + "ms request time is: "
				+ requestTime.get() + ", worker time is: " + workerTime.get());

	}

	@Test
	public void testOptimized02() throws InterruptedException {
		PushStreamProvider psp = new PushStreamProvider();
		PromiseFactory pf = new PromiseFactory(Executors.newSingleThreadExecutor());
		SimplePushEventSource<String> spes = psp.createSimpleEventSource(String.class);

		pf.submit(() -> {
			boolean active = true;
			while (active) {
				if (spes.isConnected() && counter.get() < messages) {
					String message = generateMessage(10, 100);
					spes.publish(message);
					// System.out.println(System.currentTimeMillis() + " pub " + message);
				}
			}
			return spes;
		});

		cdl = new CountDownLatch(messages);
		long time = System.currentTimeMillis();
		PushStream<String> ps1 = psp.buildStream(spes).withExecutor(Executors.newCachedThreadPool()).withParallelism(8)
				.withPushbackPolicy(PushbackPolicyOption.FIXED, 10).withQueuePolicy(QueuePolicyOption.BLOCK)
				.withBuffer(new ArrayBlockingQueue<PushEvent<? extends String>>(100)).build();
		ps1.onError(t -> System.err.println("Pushstream error: " + t.getMessage())).forEach(this::consumeMessage);

		assertTrue(cdl.await(360, TimeUnit.SECONDS));
		System.out.println("MultiThreaded 8x buffer 100 fixed 10 test took " + (System.currentTimeMillis() - time)
				+ "ms request time is: " + requestTime.get() + ", worker time is: " + workerTime.get());

	}
	
	@Test
	public void testOptimized() throws InterruptedException {
		FixedGradePushbackPolicy<String, BlockingQueue<PushEvent<? extends String>>> streamPolicy = new FixedGradePushbackPolicy<String, BlockingQueue<PushEvent<? extends String>>>("stream", 80, 5);
//		GradualBreakingQueuePolicy<String, BlockingQueue<PushEvent<? extends String>>> sourcePolicy = new GradualBreakingQueuePolicy<String, BlockingQueue<PushEvent<? extends String>>>("source", 80, 2);
		
		PushStreamProvider psp = new PushStreamProvider();
		PromiseFactory pf = new PromiseFactory(Executors.newSingleThreadExecutor());
		SimplePushEventSource<String> spes = psp.buildSimpleEventSource(String.class)
//				.withQueuePolicy(sourcePolicy)
				.withQueuePolicy(QueuePolicyOption.BLOCK)
				.withBuffer(new ArrayBlockingQueue<PushEvent<? extends String>>(50)).build();
		pf.submit(() -> {
			boolean active = true;
			while (active) {
				if (spes.isConnected() && counter.get() < messages) {
					String message = generateMessage(10, 50);
					timeMap.put(message, System.currentTimeMillis());
					spes.publish(message);
					// System.out.println(System.currentTimeMillis() + " pub " + message);
				}
			}
			return spes;
		});
		
		cdl = new CountDownLatch(messages);
		long time = System.currentTimeMillis();
		PushStream<String> ps1 = psp.buildStream(spes).withExecutor(Executors.newCachedThreadPool()).withParallelism(8)
				.withPushbackPolicy(streamPolicy)
//				.withPushbackPolicy(PushbackPolicyOption.ON_FULL_FIXED, 10)
				.withQueuePolicy(QueuePolicyOption.BLOCK)
				.withBuffer(new ArrayBlockingQueue<PushEvent<? extends String>>(100)).build();
		ps1.onError(t -> System.err.println("Pushstream error: " + t.getMessage())).forEach(this::consumeMessage);
		
		assertTrue(cdl.await(360, TimeUnit.SECONDS));
		System.out.println("MultiThreaded 8x buffer 100 fixed 10 test took " + (System.currentTimeMillis() - time)
				+ "ms request time is: " + requestTime.get() + ", worker time is: " + workerTime.get());
		
	}

	private String generateMessage(int min, int max) throws InterruptedException {
		int randomWait = ThreadLocalRandom.current().nextInt(min, max);
		Thread.sleep(randomWait);
		requestTime.addAndGet(randomWait);
		long cnt = counter.incrementAndGet();
		String msg = "message-" + cnt + "[" + randomWait + "]";
		if (cnt % 100 == 0) {
			System.out.println(System.currentTimeMillis() + " pub " + msg);
		}
		return msg;
	}

	private void consumeMessage(String message) {
		int randomWait = ThreadLocalRandom.current().nextInt(190, 510);
		workerTime.addAndGet(randomWait);
		try {
			Thread.sleep(randomWait);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		cdl.countDown();
		Long start = timeMap.remove(message);
		long systemTime = -1;
		if (start != null) {
			systemTime = System.currentTimeMillis() - start;
		}
		if (cdl.getCount() % 100 == 0) {
			System.out.println(System.currentTimeMillis() + " sub " + message + "[" + randomWait + "] took " + systemTime + "ms");
		}
	}

}
