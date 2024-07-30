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
import org.osgi.util.pushstream.PushStream;

/**
 * Messaging service interface to handle message related configurations
 * @author Mark Hoffmann
 * @since 10.10.2017
 */
@ProviderType
public interface MessagingService extends MessagingConstants {
	
	/**
	 * Subscribe the {@link PushStream} to the given topic
	 * @param topic the MQTT topic to subscribe
	 * @return a {@link PushStream} instance for the given topic
	 * @throws Exception thrown on errors
	 */
	public PushStream<Message> subscribe(String topic) throws Exception;
	
	/**
	 * Subscribe the {@link PushStream} to the given topic with a certain quality of service
	 * @param topic the message topic to subscribe
	 * @param context the optional properties in the context
	 * @return a {@link PushStream} instance for the given topic
	 * @throws Exception thrown on errors
	 */
	public PushStream<Message> subscribe(String topic, MessagingContext context) throws Exception;
	
	public void publish(String topic, ByteBuffer content) throws Exception;
	
	public void publish(String topic, ByteBuffer content, MessagingContext context) throws Exception;
	
}
