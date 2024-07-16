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

import org.gecko.adapter.mqtt.MqttConfig;

@FunctionalInterface
interface MqttClientFactory<C extends GeckoMqttClient> {
	C createClient(MqttConfig config, String id);
}