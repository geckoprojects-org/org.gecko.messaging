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
package org.gecko.adapter.mqtt;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition
public @interface MqttConfig {

	@AttributeDefinition(description = "Broker URL")
	String brokerUrl();

	@AttributeDefinition(description = "User name")
	String username();

	@AttributeDefinition(description = "Password of the user. Deprecated use _password instead.")
	@Deprecated
	String password();
	
	@AttributeDefinition(type = AttributeType.PASSWORD, description = "Password of the user")
	String _password();

	@AttributeDefinition(description = "Type of persistence")
	PersistenceType inflightPersistence() default PersistenceType.MEMORY;

	@AttributeDefinition(description = "Path for file persistence")
	String filePersistencePath() default "";

	@AttributeDefinition(description = "Maximum count of threads")
	int maxThreads() default 0;

	@AttributeDefinition(description = "Maximum inflight messages for the broker")
	int maxInflight() default 10;

}