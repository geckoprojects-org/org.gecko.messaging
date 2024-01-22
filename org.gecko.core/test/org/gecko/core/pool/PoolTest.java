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
package org.gecko.core.pool;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.LinkedList;
import java.util.List;

import org.gecko.core.pool.exception.PoolException;
import org.junit.jupiter.api.Test;

/**
 * 
 * @author jalbert
 * @since 30 Oct 2019
 */
public class PoolTest {

	
	
	@Test
	public void testNotInitialized() {
		Pool<Object> pool = new Pool<Object>("TestPool", () -> new Object(), o -> {}, 5, 10);
		assertThrows(PoolException.class, ()->pool.poll());
	}

	@Test
	public void testReInitialized() {
		Pool<Object> pool = new Pool<Object>("TestPool", () -> new Object(), o -> {}, 5, 10);
		pool.initialize();
		
		pool.poll();
		
		pool.dispose();
		
		try {
			pool.poll();
			fail("We should not reach this peace of code");
		} catch (PoolException e) {
		}
		
		pool.initialize();
		pool.poll();
	}
	
	@Test
	public void testFiled() {

		Pool<Object> pool = new Pool<Object>("TestPool", () -> new Object(), o -> {}, 5, 10);
		pool.initialize();
		
		Object o = pool.poll();
		
		assertNotNull(o);
		assertNotNull(pool.poll());
		assertNotNull(pool.poll());
		assertNotNull(pool.poll());
		assertNotNull(pool.poll());
		
		long start = System.currentTimeMillis();		
		try {
			pool.poll();
			fail("We should not reach this peace of code");
		} catch (PoolException e) {
			assertTrue((System.currentTimeMillis() - start) >= 10);
		}
		
		pool.release(o);
		assertNotNull(pool.poll());
	}

	@Test
	public void testSizeChange() {

		int poolSize = 10;
		
		Pool<Object> pool = new Pool<Object>("TestPool", () -> new Object(), o -> {}, poolSize, 10);
		pool.initialize();
		
		List<Object> objects = new LinkedList<>();
		
		for(int i = 0; i < poolSize; i++) {
			objects.add(pool.poll());
		}
		
		long start = System.currentTimeMillis();		
		try {
			pool.poll();
			fail("We should not reach this peace of code");
		} catch (PoolException e) {
			assertTrue((System.currentTimeMillis() - start) >= 10);
		}
		
		pool.modifyPoolSize(poolSize + 1 );
		
		assertNotNull(pool.poll());
		
		start = System.currentTimeMillis();		
		try {
			pool.poll();
			fail("We should not reach this peace of code");
		} catch (PoolException e) {
			assertTrue((System.currentTimeMillis() - start) >= 10);
		}
		
	}
	
}
