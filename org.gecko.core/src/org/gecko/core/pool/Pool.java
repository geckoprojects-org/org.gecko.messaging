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

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.gecko.core.pool.exception.PoolException;

/**
 * A generic implementation of an Object pool.
 * 
 * @author Juergen Albert
 * @since 30 Oct 2019
 */
public class Pool<T extends Object> {

	private final LinkedBlockingDeque<T> inUse = new LinkedBlockingDeque<T>();
	private final LinkedBlockingDeque<T> available = new LinkedBlockingDeque<T>();
	private final Supplier<T> pooledObjectSupplier;
	private final Consumer<T> releaseConsumer;
	private final AtomicInteger currentPoolSize;
	
	private final Executor executor = Executors.newFixedThreadPool(4);
	
	private String name;
	private long pollTimeoutMillis;
	
	private boolean initialized = false;;
	
	/**
	 * Creates a new instance.
	 * @param name A name for this Pool
	 * @param pooledObjectSupplier creates the Objects the pool is filled with
	 * @param releaseConsumer handles the dispose of pooled instances
	 * @param startPoolSize the initial size of the pool
	 * @param pollTimeoutMillis how many Milliseconds the pool will wait until the poll times out
	 */
	public Pool(String name, Supplier<T> pooledObjectSupplier, Consumer<T> releaseConsumer, int startPoolSize, long pollTimeoutMillis) {
		this.name = name;
		this.pooledObjectSupplier = pooledObjectSupplier;
		this.releaseConsumer = releaseConsumer;
		this.pollTimeoutMillis = pollTimeoutMillis;
		this.currentPoolSize = new AtomicInteger(startPoolSize);
	}
	
	/**
	 * Fills the pool and sets itself to ready
	 */
	public void initialize() {
		for(int i  = 0; i < currentPoolSize.get(); i++) {
			available.add(pooledObjectSupplier.get());
		}
		initialized = true;
	}
	
	private void checkInitializationState() {
		if(!initialized) {
			throw new PoolException("The Pool[" + name + "] needs is eather not intialized or already disposed.");
		}
	}
	
	/**
	 * Adjusts the current size of the pool. If the size increases, new instances will be added synchronously.
	 * If the new size is smaller then the current size, this method will return immediately. The next instances 
	 * that get released, will be disposed until the desired size is reached.  
	 * @param size
	 */
	public void modifyPoolSize(int size) {
		checkInitializationState();
		int currentSize = currentPoolSize.getAndSet(size);
		if(currentSize < size){
			for(int i = 0 ; i < size - currentSize; i++){
				available.offer(pooledObjectSupplier.get());
			}
		}
	}
	
	/**
	 * Disposes all Objects and clears the pool. It can be reused by calling initialize again.
	 */
	public void dispose() {
		inUse.forEach(releaseConsumer);
		inUse.clear();
		available.forEach(releaseConsumer);
		available.clear();
		initialized = false;
	}
	
	/**
	 * Polls for an Instance. If no instance can be acquired in the defined default timeout, 
	 * a {@link PoolException} is thrown.
	 * @return the desired instance
	 */
	public T poll() {
		return poll(pollTimeoutMillis);
	}

	/**
	 * Polls for an Instance. If no instance can be acquired before the given timeout is reached, 
	 * a {@link PoolException} is thrown.
	 * @param a timeout in milliseconds for the poll
	 * @return the desired instance
	 */
	public T poll(long timeout) {
		checkInitializationState();
		try {
			T instance = available.poll(timeout, TimeUnit.MILLISECONDS);
			if(instance == null) {
				throw new PoolException("Pool[" + name + "] couldn't aquire a new instance in " + pollTimeoutMillis + " ms"); 
			}
			inUse.offer(instance);
			return instance;
		} catch (InterruptedException e) {
			throw new PoolException("Somewhing went wrong while quireing an Object from the Pool[" + name + "]", e);
		}
	}
	
	/**
	 * Retunrs the given instance to the pool.
	 * @param instance the instance to release
	 */
	public void release(final T instance) {
		checkInitializationState();
		executor.execute(() -> {
			int actualPoolSize = available.size() + inUse.size();
			boolean removed = inUse.removeFirstOccurrence(instance);
			if (removed) {
				if(actualPoolSize > currentPoolSize.get()){
					releaseConsumer.accept(instance);
				} else {
					available.offer(instance);
				}
			}
		});
	}
	
}
