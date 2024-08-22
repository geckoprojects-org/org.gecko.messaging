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

package org.gecko.adapter.amqp.api;

/**
 * Additional AMQP Properties
 * @author Mark Hoffmann
 * @since 28.02.2024
 */
public @interface AMQPProperties {
	
	static final String PREFIX_ = "amqp.";
	
	int maxActivateRPCWorker() default 3;
	boolean singleActiveConsumer() default false;
	boolean exclusiveQueue() default false;
	int basicQos() default 0;
	boolean autoAcknowledge() default false;
	
}
