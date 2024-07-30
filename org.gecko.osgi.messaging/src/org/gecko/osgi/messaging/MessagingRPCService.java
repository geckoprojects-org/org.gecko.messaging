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
