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

/**
 * 
 * @author mark
 * @since 28.02.2024
 */
public @interface AMQPProperties {
	
	static final String PREFIX_ = "amqp.";
	
	boolean singleActiveConsumer() default false;
	boolean exclusiveQueue() default false;
	int basicQos() default 0;
	boolean autoAcknowledge() default false;
	
}
