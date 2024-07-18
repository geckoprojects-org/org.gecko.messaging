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

package org.gecko.adapter.mqtt.service;

import org.gecko.adapter.mqtt.MqttConfig;
import org.gecko.adapter.mqtt.common.AbstractMqttService;
import org.gecko.adapter.mqtt.common.GeckoMqttClient;
import org.gecko.osgi.messaging.MessagingConstants;
import org.gecko.osgi.messaging.MessagingService;
import org.osgi.annotation.bundle.Capability;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;
import org.osgi.service.metatype.annotations.Designate;

/**
 * MQTT messaging service implementation for version 5
 * 
 */
@Capability(namespace = MessagingConstants.CAPABILITY_NAMESPACE, name = "mqtt.adapter", version = "1.0.0", attribute = {
		"vendor=Gecko.io", "implementation=Paho", "mqttVersion=5" })
@Designate(factory = true, ocd = MqttConfig.class)
@Component(service = MessagingService.class, name = "MQTTService", scope = ServiceScope.PROTOTYPE)
public class MQTTService extends AbstractMqttService {

	public MQTTService() {
		// to be used with @Activate
	}

	public MQTTService(GeckoMqttClient mqtt) {
		this.mqtt = mqtt;
	}

	@Override
	protected GeckoMqttClient createClient(MqttConfig config, String id) {
		return new PahoV5Client(config, id);
	}

}