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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

/**
 * 
 * @author mark
 * @since 29.01.2019
 */
public class AMQPConsumerMetric implements AMQPConsumerMetricMBean {
	
	private String name;
	private String queueName;
	private String exchange;
	private String routingKey;
	private Date lastMessageTime;
	private BlockingQueue<Long> timeQueue = new ArrayBlockingQueue<>(100, true);
	private ObjectName objectName;
	
	/**
	 * Creates a new instance.
	 */
	public AMQPConsumerMetric() {
	}

	public String getName() {
		return name;
	}
	
	/**
	 * Sets the name.
	 * @param name the name to set
	 */
	public void setName(String name) {
		this.name = name;
	}
	
	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.jmx.AMQPConsumerMBean#getQueueName()
	 */
	@Override
	public String getQueueName() {
		return queueName;
	}
	
	/**
	 * Sets the queueName.
	 * @param queueName the queueName to set
	 */
	public void setQueueName(String queueName) {
		this.queueName = queueName;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.jmx.AMQPConsumerMBean#getExchange()
	 */
	@Override
	public String getExchange() {
		return exchange;
	}
	
	/**
	 * Sets the exchange.
	 * @param exchange the exchange to set
	 */
	public void setExchange(String exchange) {
		this.exchange = exchange;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.jmx.AMQPConsumerMBean#getRoutingKey()
	 */
	@Override
	public String getRoutingKey() {
		return routingKey;
	}
	
	/**
	 * Sets the routingKey.
	 * @param routingKey the routingKey to set
	 */
	public void setRoutingKey(String routingKey) {
		this.routingKey = routingKey;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.jmx.AMQPConsumerMBean#getLastMessageTime()
	 */
	@Override
	public Date getLastMessageTime() {
		return lastMessageTime;
	}
	
	/**
	 * Sets the lastMessageTime.
	 * @param lastMessageTime the lastMessageTime to set
	 */
	public void setLastMessageTime(Date lastMessageTime) {
		Date last = this.lastMessageTime;
		if (last != null) {
			long time = lastMessageTime.getTime() - last.getTime();
			if (timeQueue.remainingCapacity() == 0) {
				timeQueue.poll();
			}
			timeQueue.add(Long.valueOf(time));
		}
		this.lastMessageTime = lastMessageTime;
		
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.jmx.AMQPConsumerMBean#getMessagePerMinute()
	 */
	@Override
	public double getMessagePerMinute() {
		return timeQueue.stream().mapToLong(Long::longValue).average().orElse(-1);
	}

	public ObjectName getObjectName() {
		String name = getName();
		if (name == null) {
			throw new IllegalStateException("A name is needed to create the object name");
		}
		if (objectName == null) {
			try {
				objectName = new ObjectName("Messaging:name=AMQPConsumer-" + getName());
			} catch (MalformedObjectNameException e) {
				e.printStackTrace();
			}
		}
		return objectName;
	}

}
