/**
 * Copyright (c) 2012 - 2017 Data In Motion and others.
 * All rights reserved. 
 * 
 * This program and the accompanying materials are made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
package org.gecko.osgi.messaging;

import java.nio.ByteBuffer;

import org.osgi.annotation.versioning.ProviderType;
import org.osgi.util.promise.Promise;

/**
 * Messaging service interface to handle message related configurations
 * @author Mark Hoffmann
 * @since 10.10.2017
 */
@ProviderType
public interface MessagingRPCService extends MessagingConstants {
	
	/**
	 * Publishes a message as RPC call
	 * @param topic the topic to publish on
	 * @param content the content
	 * @return the promise for the result
	 * @throws Exception
	 */
	public Promise<Message> publishRPC(String topic, ByteBuffer content) throws Exception;
	
	/**
	 * Publishes a message as RPC call
	 * @param topic the topic to publish on
	 * @param content the content
	 * @param context the additional context object for sending
	 * @return the promise for the result
	 * @throws Exception
	 */
	public Promise<Message> publishRPC(String topic, ByteBuffer content, MessagingContext context) throws Exception;
	
}
