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

import org.gecko.osgi.messaging.SimpleMessagingContext;

/**
 * MQTT context to provide additional configurations
 * @author Mark Hoffmann
 * @since 10.10.2017
 */
public class MQTTContext extends SimpleMessagingContext {
	
	private QoS qos;
	private boolean retained = false;
	
	public QoS getQoS() {
		return qos;
	}
	
	public void setQoS(QoS qos) {
		this.qos = qos;
	}
	
	public boolean isRetained() {
		return retained;
	}
	
	public void setRetained(boolean retained) {
		this.retained = retained;
	}

}
