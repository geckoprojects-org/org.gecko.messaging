/**
 * Copyright (c) 2012 - 2024 Data In Motion and others.
 * All rights reserved. 
 * 
 * This program and the accompanying materials are made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
package org.gecko.adapter.amqp.api;

import java.io.IOException;

/**
 * This callback contains methods to handle a re-subscription.
 * The closing of the consumer is the first step. The second step is resolving 
 * the re-subscription promise, when the corresponding condition is met with a
 * given value.
 * @author Mark Hoffmann
 * @since 28.02.2024
 */
public interface ReSubscribeCallback<T> {
	
	/**
	 * Should return <code>true</code>, when a consumer should be stopped / closed.
	 * @return <code>true</code>, if the consumer should be closed, otherwise <code>false</code>
	 */
	boolean shouldCloseConsumer() throws IOException;
	
	/**
	 * Returns <code>true</code>, if a re-subscribe should be triggered.
	 * @return <code>true</code>, if a re-subscribe should be triggered.
	 */
	boolean shouldResubscribe();
	
	/**
	 * Returns the value for a re-subscription trigger. This is usually
	 * a value provided to resolve a re-subscription promise.
	 */
	T getCloseValue();

}
