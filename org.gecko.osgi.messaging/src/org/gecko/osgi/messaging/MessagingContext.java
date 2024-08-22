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

import org.gecko.util.pushstream.PushStreamContext;

/**
 * Context object that can be used to provide additional configuration to the adapter
 * @author Mark Hoffmann
 * @since 10.10.2017
 */
public interface MessagingContext extends PushStreamContext<Message> {

	public static final String PROP_REPLY_TO_POLICY = "replyToPolicy";

	/**
	 * Returns the queue name
	 * @return the queue name
	 */
	public String getQueueName();

	/**
	 * Returns the routing key
	 * @return the routing key
	 */
	public String getRoutingKey();

	/**
	 * Returns the content type
	 * @return the content type
	 */
	public String getContentType();

	/**
	 * Returns the content encoding
	 * @return the content encoding
	 */
	public String getContentEncoding();

	/**
	 * Returns the correlation id
	 * @return the correlation id
	 */
	public String getCorrelationId();

	/**
	 * Returns the reply address
	 * @return the reply address
	 */
	public String getReplyAddress();

	/**
	 * Returns the policy how many results are expected
	 * @return the policy how many results are expected
	 */
	public ReplyToPolicy getReplyPolicy();
	
	public String getSoure();
	
	public String getId();

}
