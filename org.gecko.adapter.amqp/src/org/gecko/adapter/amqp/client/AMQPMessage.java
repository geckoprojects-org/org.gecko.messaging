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
package org.gecko.adapter.amqp.client;

import org.gecko.osgi.messaging.Message;

/**
 * AMQP message object
 * @author Mark Hoffmann
 * @since 10.12.2018
 */
public interface AMQPMessage extends Message {

	String getRoutingKey();
	
	String getExchange();
	
	long getDeliveryTag();
	
	String getReplyTo();
	
	String getCorrelationId();
	
	String getContentType();
	
	String getMessageId();
	
	/**
	 * Returns <code>true</code>, if the message seems to be an RPC call
	 * @return <code>true</code>, if the message seems to be an RPC call
	 */
	boolean isRPC();
	
}
