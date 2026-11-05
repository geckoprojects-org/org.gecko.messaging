/**
 * Copyright (c) 2012 - 2026 Data In Motion and others.
 * All rights reserved.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
package org.gecko.messaging.core;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs tasks one after the other, in submission order, on a shared executor — without a thread of
 * its own.
 */
final class SerialExecutor implements Executor {

	private static final Logger LOG = Logger.getLogger(SerialExecutor.class.getName());

	private final Executor delegate;
	private final Queue<Runnable> tasks = new ArrayDeque<>();
	private boolean running;

	SerialExecutor(Executor delegate) {
		this.delegate = delegate;
	}

	@Override
	public void execute(Runnable task) {
		synchronized (tasks) {
			tasks.add(task);
			if (running) {
				return;
			}
			running = true;
		}
		try {
			delegate.execute(this::drain);
		} catch (RejectedExecutionException e) {
			synchronized (tasks) {
				running = false;
				tasks.clear();
			}
		}
	}

	private void drain() {
		while (true) {
			Runnable next;
			synchronized (tasks) {
				next = tasks.poll();
				if (next == null) {
					running = false;
					return;
				}
			}
			try {
				next.run();
			} catch (RuntimeException e) {
				LOG.log(Level.WARNING, "A serial task failed", e);
			}
		}
	}

}
