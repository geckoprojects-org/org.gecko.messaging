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
 * 
 * @author mark
 * @since 28.02.2024
 */
public interface ReSubscribeCallback<T> {
	
	/**
	 * Should be extended by clients
	 * @return <code>true</code>, if the consumer should be closed, otherwise <code>false</code>
	 */
	boolean shouldCloseConsumer() throws IOException;
	
	/**
	 * Can be overloaded to execute some custom action before closing the consumer
	 */
	void preCloseConsumer() throws IOException;

	/**
	 * Can be overloaded to execute some custom action when closing the consumer
	 */
	void closeConsumer() throws IOException;
	
	/**
	 * Can be overloaded to execute some custom action when closing the channel
	 */
	void closeChannel() throws IOException;
	
	/**
	 * Returns the value for a closing condition, that is provided to resolve the promise
	 * @return the value for a closing condition
	 */
	T getCloseValue();

}
