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

package org.gecko.adapter.amqp;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * 
 * @author mark
 * @since 29.01.2019
 */
public class RingBufferTest {

	@Test
	public void testJoin() {
		BlockingQueue<Long> timeQueue = new ArrayBlockingQueue<>(3, true);
		for (int i = 0; i < 10; i++) {
			if (timeQueue.remainingCapacity() == 0) {
				timeQueue.poll();
			}
			timeQueue.add(Long.valueOf(i));
			System.out.println(String.format("[%s] content %s", i, timeQueue.stream().map(l->String.valueOf(l)).collect(Collectors.joining(";"))));
		}
	}
	
	@Test
	public void testAverage() {
		BlockingQueue<Long> timeQueue = new ArrayBlockingQueue<>(3, true);
		for (int i = 0; i < 10; i++) {
			if (timeQueue.remainingCapacity() == 0) {
				timeQueue.poll();
			}
			timeQueue.add(Long.valueOf(i));
			System.out.println(String.format("[%s] average %s", i, timeQueue.stream().mapToLong(Long::longValue).average().orElse(-1)));
		}
	}

}
