/**
 * Copyright (c) 2012 - 2024 Data In Motion and others.
 * All rights reserved. 
 * 
 * This program and the accompanying materials are made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
package org.gecko.adapter.amqp.api;

import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * 
 * @author mark
 * @since 26.02.2024
 */
@ObjectClassDefinition
public @interface AMQPConfiguration {
	
	String name();
	String username();
	String password();
	String host() default "localhost";
	int port() default 5672;
	String virtualHost() default "/";
	String keyStore();
	String keyStorePassword();
	String trustStore();
	String trustStorePassword();
	boolean tls() default false;
	String exchange();
	String topic();
	String routingKey();
	boolean autoRecovery() default true;
	String brokerUrl();
	boolean immediateChannel() default false;

}
