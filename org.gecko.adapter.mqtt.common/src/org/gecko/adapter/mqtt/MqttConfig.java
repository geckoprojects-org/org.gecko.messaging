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

package org.gecko.adapter.mqtt;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition
public @interface MqttConfig {

	@AttributeDefinition(description = "Broker URL")
	String brokerUrl();

	@AttributeDefinition(description = "User name")
	String username() default "guest";

	@AttributeDefinition(description = "Password of the user. Deprecated use _password instead.")
	@Deprecated
	String password() default "guest";
	
	@AttributeDefinition(type = AttributeType.PASSWORD, description = "Password of the user")
	String _password() default "guest";

	@AttributeDefinition(description = "Type of persistence")
	PersistenceType inflightPersistence() default PersistenceType.MEMORY;

	@AttributeDefinition(description = "Path for file persistence")
	String filePersistencePath() default "";

	@AttributeDefinition(description = "Maximum count of threads")
	int maxThreads() default 0;

	@AttributeDefinition(description = "Maximum inflight messages for the broker")
	int maxInflight() default 10;

}