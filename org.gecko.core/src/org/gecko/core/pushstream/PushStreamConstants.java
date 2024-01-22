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
package org.gecko.core.pushstream;

import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.SimplePushEventSource;

/**
 * Constants for configuring a push stream
 * @author Mark Hoffmann
 * @since 29.11.2018
 */
public interface PushStreamConstants {
	
	/**
	 * Options for the {@link SimplePushEventSource}
	 */
	public static final String PROP_SES_BUFFER_SIZE = "pushstream.ses.bufferSize";
	public static final String PROP_SES_BUFFER_QUEUE = "pushstream.ses.bufferQueue";
	public static final String PROP_SES_QUEUE_POLICY = "pushstream.ses.queue.policy";
	public static final String PROP_SES_QUEUE_POLICY_BY_NAME = "pushstream.ses.queue.policy.name";
	public static final String PROP_SES_QUEUE_POLICY_OPTION = "pushstream.ses.queue.policyOption";
	/**
	 * Options for the {@link PushStream}
	 */
	public static final String PROP_BUFFER_SIZE = "pushstream.bufferSize";
	public static final String PROP_PARALLELISM = "pushstream.parallelism";
	public static final String PROP_EXECUTOR = "pushstream.executorService";
	public static final String PROP_SCHEDULED_EXECUTOR = "pushstream.scheduledExecutorService";
	public static final String PROP_BUFFER_QUEUE = "pushstream.bufferQueue";
	public static final String PROP_QUEUE_POLICY = "pushstream.queue.policy";
	public static final String PROP_QUEUE_POLICY_OPTION = "pushstream.queue.policyOption";
	public static final String PROP_QUEUE_POLICY_BY_NAME = "pushstream.queue.policy.name";
	public static final String PROP_PUSHBACK_POLICY = "pushstream.pushback.policy";
	public static final String PROP_PUSHBACK_POLICY_TIME = "pushstream.pushback.policyTime";
	public static final String PROP_PUSHBACK_POLICY_OPTION = "pushstream.pushback.policyOption";
	public static final String PROP_PUSHBACK_POLICY_OPTION_BY_NAME = "pushstream.pushback.policyOption.name";
	public static final String PROP_ACK_FILTER = "pushstream.ack.filter";
	public static final String PROP_ACK_CONSUMER = "pushstream.ack.function";
	public static final String PROP_NACK_CONSUMER = "pushstream.nack.function";
	public static final String PROP_ACK_ERROR = "pushstream.ack.errorFunction";
	
}
