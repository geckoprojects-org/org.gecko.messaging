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

package org.gecko.adapter.amqp.jmx;

import java.util.Date;

/**
 * JMX bean for a AMQP consumer
 * @author Mark Hoffmann
 * @since 29.01.2019
 */
public interface AMQPConsumerMetricMBean {
	
	String getQueueName();
	String getExchange();
	String getRoutingKey();
	Date getLastMessageTime();
	double getMessagePerMinute();

}
