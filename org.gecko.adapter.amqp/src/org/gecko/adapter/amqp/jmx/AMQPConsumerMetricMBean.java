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
