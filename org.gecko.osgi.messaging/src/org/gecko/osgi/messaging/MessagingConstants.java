/**
 * Copyright (c) 2012 - 2017 Data In Motion and others.
 * All rights reserved. 
 * 
 * This program and the accompanying materials are made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
package org.gecko.osgi.messaging;

/**
 * Constants for the messaging
 * @author Mark Hoffmann
 * @since 10.10.2017
 */
public interface MessagingConstants {
	
	/** Connection URL for the broker */
	public static final String PROP_BROKER = "brokerUrl";
	public static final String PROP_USERNAME = "username";
	public static final String PROP_PASSWORD = ".password";
	public static final String PROP_RPC_QUEUE = "rpcQueue";
	public static final String PROP_RPC_EXCHANGE = "rpcQueue";
	public static final String PROP_RPC_ROUTING_KEY = "rpcRoutingKey";
	
	/** Names space for the message adapter capability */
	public static final String CAPABILITY_NAMESPACE = "osgi.message.adapter";
	
	public static final String EVENTADMIN_ADAPTER = "eventadmin.adapter";
	public static final String EVENTADMIN_ADAPTER_VERSION = "1.0.0";
	

//	public static final String PROP_SUBSCRIBE_TOPICS = "message.subscribe.topics";
//	
//	public static final String PROP_PUBLISH_TOPICS = "message.publish.topics";
//	/** Set to true, if it is allowed to publish on subscribed topics */
//	public static final String PROP_PUBLISH_ON_SUBSCRIBE = "message.publishOnSubcribe";
	

}
