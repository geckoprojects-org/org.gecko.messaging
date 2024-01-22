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
package org.gecko.core.api.concurrent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Helper class to correct shut-down {@link ExecutorService}
 * @author Mark Hoffmann
 * @since 12.02.2019
 */
public class ExecutorHelper {
	
	private static Logger logger = Logger.getLogger(ExecutorHelper.class.getName());
	
	/**
	 * Executes a correct {@link ExecutorService} shutdown in a two phase. First it stops accepting new tasks.
	 * After that running tasks are canceled, if needed.
	 * 
	 * @param executorService the executor service
	 * @param awaitMillis milliseconds for the await termination. For 0 or negative values 10ms is assumed.
	 * @return <code>false</code>, if the executor service is <code>null</code>, otherwise the {@link ExecutorService#isTerminated()} state
	 */
	public static boolean shutdownExecutorServiceWithAwait(ExecutorService executorService, long awaitMillis) {
		if (executorService == null) {
			return false;
		}
		awaitMillis = awaitMillis < 0 ? 10 : awaitMillis; 
		executorService.shutdown();
		try {
			if (!executorService.awaitTermination(awaitMillis, TimeUnit.MILLISECONDS)) {
				executorService.shutdownNow();
				if (!executorService.awaitTermination(awaitMillis, TimeUnit.MILLISECONDS)) {
					logger.severe("Executor did not terminate");
				}
			}
		} catch (InterruptedException e) {
			executorService.shutdownNow();
			Thread.currentThread().interrupt();
		}
		return executorService.isTerminated();
	}

}
