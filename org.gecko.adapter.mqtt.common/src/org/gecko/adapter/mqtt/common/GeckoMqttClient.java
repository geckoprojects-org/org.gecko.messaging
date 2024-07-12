/**
 * Copyright (c) 2012 - 2024 Data In Motion and others.
 * All rights reserved. 
 * 
 * This program and the accompanying materials are made available under the terms of the 
 * Eclipse Public License v2.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 * 
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
package org.gecko.adapter.mqtt.common;

import java.util.function.Consumer;
import java.util.function.Function;

import org.gecko.adapter.mqtt.MqttConfig;
import org.osgi.util.pushstream.PushEventSource;

/**
 * Facade for different implementations or versions of a MQTT client
 */
public interface GeckoMqttClient {

	/**
	 * Connect the client with a given configuration {@link MqttConfig}.
	 * 
	 * @param config      Configuration
	 * @param onException function executed if an Exception occurs on connection
	 * @return <code>true</code> if connected, <code>false</code> if an error occur
	 */
	boolean connect(MqttConfig config, Function<Exception, Boolean> onException);

	/**
	 * Client is Connected
	 * 
	 * @return <code>true</code> if client is connected, else <code>false</code>
	 */
	boolean isConnected();

	/**
	 * Disconnects the client
	 */
	void disconnect();

	/**
	 * close the connection
	 */
	void close();

	/**
	 * Subscribes to a topic with a quality of service. Incoming messages will be
	 * published to the MqttPushEventSource.
	 * 
	 * @param topic Topic
	 * @param qos   Quality of Service
	 * @param src   {@link PushEventSource} for incoming messages
	 */
	void subscribe(String topic, int qos, MqttPushEventSource src);

	/**
	 * Publish content to a broker  
	 * 
	 * @param topic Topic 
	 * @param content Content
	 * @param qos Quality of service
	 * @param retained <code>true</code> to add retrained flag to message.
	 * @throws Exception
	 */
	void publish(String topic, byte[] content, int qos, boolean retained) throws Exception;

	/**
	 * Connection lost handling 
	 * 
	 * @param reconnectConsumer consumer executed on connection lost
	 */
	void connectionLost(Consumer<Throwable> reconnectConsumer);

}
