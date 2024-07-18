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

import java.util.LinkedList;
import java.util.List;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

/**
 * JMX Bean implementation
 * @author Mark Hoffmann
 * @since 29.01.2019
 */
public class AMQPServiceMetric implements AMQPServiceMetricMBean {
	
	private List<String> channels = new LinkedList<String>();
	private int bufferSize = 32;
	private String queuePolicyName = "FAIL";
	private ObjectName objectName;
	private String name;
	private String host;
	private String vhost;
	private int port;
	
	/**
	 * Creates a new instance.
	 */
	public AMQPServiceMetric() {
	}
	
	public void addChannel(String channelName) {
		channels.add(channelName);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.jmx.AMQPServiceMBean#getChannels()
	 */
	@Override
	public String[] getChannels() {
		return channels.toArray(new String[channels.size()]);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.jmx.AMQPServiceMBean#getNumberChannels()
	 */
	@Override
	public int getNumberChannels() {
		return channels.size();
	}
	
	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.jmx.AMQPServiceMBean#getEventSourceBufferSize()
	 */
	@Override
	public int getEventSourceBufferSize() {
		return bufferSize;
	}
	
	/**
	 * Sets the bufferSize.
	 * @param bufferSize the bufferSize to set
	 */
	public void setEventSourceBufferSize(int bufferSize) {
		this.bufferSize = bufferSize;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.jmx.AMQPServiceMBean#getEventSourceQueuePolicy()
	 */
	@Override
	public String getEventSourceQueuePolicy() {
		return queuePolicyName;
	}
	
	/**
	 * Sets the queuePolicyName.
	 * @param queuePolicyName the queuePolicyName to set
	 */
	public void setEventSourceQueuePolicy(String queuePolicyName) {
		this.queuePolicyName = queuePolicyName;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.jmx.AMQPServiceMBean#getHost()
	 */
	@Override
	public String getHost() {
		return host;
	}
	
	/**
	 * Sets the host.
	 * @param host the host to set
	 */
	public void setHost(String host) {
		this.host = host;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.jmx.AMQPServiceMBean#getVHost()
	 */
	@Override
	public String getVHost() {
		return vhost;
	}
	
	/**
	 * Sets the vhost.
	 * @param vhost the vhost to set
	 */
	public void setVHost(String vhost) {
		this.vhost = vhost;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.jmx.AMQPServiceMBean#getPort()
	 */
	@Override
	public int getPort() {
		return port;
	}
	
	/**
	 * Sets the port.
	 * @param port the port to set
	 */
	public void setPort(int port) {
		this.port = port;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.jmx.AMQPServiceMBean#getName()
	 */
	@Override
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
	
	public ObjectName getObjectName() {
		if (objectName == null) {
			try {
				objectName = new ObjectName("Messaging:name=" + getName());
			} catch (MalformedObjectNameException e) {
				e.printStackTrace();
			}
		}
		return objectName;
	}

}
