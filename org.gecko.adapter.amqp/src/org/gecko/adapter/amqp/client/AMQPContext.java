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

package org.gecko.adapter.amqp.client;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.gecko.adapter.amqp.api.AMQPProperties;
import org.gecko.osgi.messaging.SimpleMessagingContext;

/**
 * Implementation of the messaging context for the AMQP protocol
 * @author Mark Hoffmann
 * @since 15.02.2018
 */
public class AMQPContext extends SimpleMessagingContext {
	
	public static enum RoutingType {
		DIRECT,
		TOPIC,
		FANOUT,
		HEADER
	}
	
	private String exchange = null;
	private String appId = null;
	private String clusterId = null;
	private String expiration = null;
	private String messageId = null;
	private String type = null;
	private String userId = null;
	private Date timestamp = null;
	private Integer priority = null;
	private Integer deliveryMode = null;
	private final Map<String, Object> header = new HashMap<String, Object>();
	private RoutingType routingType = RoutingType.DIRECT;
	private boolean durable = false;
	private boolean exclusive = false;
	private boolean autoDelete = false;
	private boolean queueMode = false;
	private boolean exchangeMode = false;
	private boolean autoAcknowledge = false;
	private boolean rpc = false;
	private AMQPProperties properties = null;
	
	/**
	 * Sets the properties.
	 * @param properties the properties to set
	 */
	void setProperties(AMQPProperties properties) {
		this.properties = properties;
	}
	
	/**
	 * Returns the properties.
	 * @return the properties
	 */
	public AMQPProperties getProperties() {
		return properties;
	}
	
	/**
	 * Adds a header
	 * @param key 
	 * @param value
	 */
	void addHeader(String key, Object value) {
		getHeader().put(key, value);
	}
	

	/**
	 * Returns the autoAcknowledge.
	 * @return the autoAcknowledge
	 */
	public boolean isAutoAcknowledge() {
		return autoAcknowledge;
	}

	/**
	 * Sets the autoAcknowledge.
	 * @param autoAcknowledge the autoAcknowledge to set
	 */
	void setAutoAcknowledge(boolean autoAcknowledge) {
		this.autoAcknowledge = autoAcknowledge;
	}

	/**
	 * Returns the routingType.
	 * @return the routingType
	 */
	public String getRoutingType() {
		return routingType.toString().toLowerCase();
	}

	/**
	 * Sets the routingType.
	 * @param routingType the routingType to set
	 */
	void setRoutingType(RoutingType routingType) {
		this.routingType = routingType;
	}

	/**
	 * Returns the exchangeName.
	 * @return the exchangeName
	 */
	public String getExchangeName() {
		return exchange;
	}
	
	/**
	 * Sets the exchangeName.
	 * @param exchangeName the exchangeName to set
	 */
	void setExchangeName(String exchangeName) {
		this.exchange = exchangeName;
	}

	/**
	 * Returns the durable.
	 * @return the durable
	 */
	public boolean isDurable() {
		return durable;
	}

	/**
	 * Sets the durable.
	 * @param durable the durable to set
	 */
	void setDurable(boolean durable) {
		this.durable = durable;
	}

	/**
	 * Returns the exclusive.
	 * @return the exclusive
	 */
	public boolean isExclusive() {
		return exclusive;
	}

	/**
	 * Sets the exclusive.
	 * @param exclusive the exclusive to set
	 */
	void setExclusive(boolean exclusive) {
		this.exclusive = exclusive;
	}

	/**
	 * Returns the autoDelete.
	 * @return the autoDelete
	 */
	public boolean isAutoDelete() {
		return autoDelete;
	}

	/**
	 * Sets the autoDelete.
	 * @param autoDelete the autoDelete to set
	 */
	void setAutoDelete(boolean autoDelete) {
		this.autoDelete = autoDelete;
	}

	/**
	 * Returns the queueMode.
	 * @return the queueMode
	 */
	boolean isQueueMode() {
		return queueMode;
	}

	/**
	 * Sets the queueMode.
	 * @param queueMode the queueMode to set
	 */
	void setQueueMode(boolean queueMode) {
		this.queueMode = isExchangeMode() ? false : queueMode;
	}

	/**
	 * Returns the exchangeMode.
	 * @return the exchangeMode
	 */
	public boolean isExchangeMode() {
		return exchangeMode;
	}

	/**
	 * Sets the exchangeMode.
	 * @param exchangeMode the exchangeMode to set
	 */
	void setExchangeMode(boolean exchangeMode) {
		this.exchangeMode = isQueueMode() ? false : exchangeMode;
	}

	/**
	 * Returns <code>true</code>, if the rpc mode is active.
	 * @return <code>true</code>, if the rpc mode is active.
	 */
	public boolean isRpc() {
		return rpc;
	}

	/**
	 * Set to <code>true</code>, if the request is in RPC mode
	 * @param rpc the mode to set
	 */
	public void setRpc(boolean rpc) {
		this.rpc = rpc;
	}

	/**
	 * Sets the app id
	 * @param appId
	 */
	void setAppId(String appId) {
		this.appId = appId;
	}
	
	/**
	 * Returns the appId.
	 * @return the appId
	 */
	public String getAppId() {
		return appId;
	}

	/**
	 * Returns the clusterId.
	 * @return the clusterId
	 */
	public String getClusterId() {
		return clusterId;
	}

	/**
	 * Sets the clusterId.
	 * @param clusterId the clusterId to set
	 */
	void setClusterId(String clusterId) {
		this.clusterId = clusterId;
	}

	/**
	 * Returns the priority.
	 * @return the priority
	 */
	public Integer getPriority() {
		return priority;
	}

	/**
	 * Sets the priority.
	 * @param priority the priority to set
	 */
	void setPriority(Integer priority) {
		this.priority = priority;
	}

	/**
	 * Returns the deliveryMode.
	 * @return the deliveryMode
	 */
	public Integer getDeliveryMode() {
		return deliveryMode;
	}

	/**
	 * Sets the deliveryMode.
	 * @param deliveryMode the deliveryMode to set
	 */
	void setDeliveryMode(Integer deliveryMode) {
		this.deliveryMode = deliveryMode;
	}

	/**
	 * Returns the timestamp.
	 * @return the timestamp
	 */
	public Date getTimestamp() {
		return timestamp;
	}

	/**
	 * Sets the timestamp.
	 * @param timestamp the timestamp to set
	 */
	void setTimestamp(Date timestamp) {
		this.timestamp = timestamp;
	}


	/**
	 * Returns the expiration.
	 * @return the expiration
	 */
	public String getExpiration() {
		return expiration;
	}


	/**
	 * Sets the expiration.
	 * @param expiration the expiration to set
	 */
	void setExpiration(String expiration) {
		this.expiration = expiration;
	}


	/**
	 * Returns the messageId.
	 * @return the messageId
	 */
	public String getMessageId() {
		return messageId;
	}


	/**
	 * Sets the messageId.
	 * @param messageId the messageId to set
	 */
	public void setMessageId(String messageId) {
		this.messageId = messageId;
	}


	/**
	 * Returns the type.
	 * @return the type
	 */
	public String getType() {
		return type;
	}


	/**
	 * Sets the type.
	 * @param type the type to set
	 */
	void setType(String type) {
		this.type = type;
	}


	/**
	 * Returns the userId.
	 * @return the userId
	 */
	public String getUserId() {
		return userId;
	}


	/**
	 * Sets the userId.
	 * @param userId the userId to set
	 */
	void setUserId(String userId) {
		this.userId = userId;
	}


	/**
	 * Returns the header.
	 * @return the header
	 */
	public Map<String, Object> getHeader() {
		return header;
	}
	
}
