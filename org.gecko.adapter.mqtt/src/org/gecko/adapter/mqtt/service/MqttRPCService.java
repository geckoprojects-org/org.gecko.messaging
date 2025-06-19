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

import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.adapter.mqtt.MqttConfig;
import org.gecko.adapter.mqtt.common.AbstractMqttRPCService;
import org.gecko.adapter.mqtt.common.GeckoMqttClient;
import org.gecko.osgi.messaging.MessagingConstants;
import org.gecko.osgi.messaging.MessagingRPCService;
import org.osgi.annotation.bundle.Capability;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;
import org.osgi.service.metatype.annotations.Designate;

/**
 * MQTT RPC messaging service implementation for version 3
 * 
 */
@Capability(namespace = MessagingConstants.CAPABILITY_NAMESPACE, name = "mqtt.rpc.adapter", version = "1.0.0", attribute = {
		"vendor=Gecko.io", "implementation=Paho", "mqttVersion=3" })
@Designate(factory = true, ocd = MqttConfig.class)
@Component(service = MessagingRPCService.class, name = "MQTTRPCService", scope = ServiceScope.PROTOTYPE)
public class MqttRPCService extends AbstractMqttRPCService {
	private static final Logger logger = Logger.getLogger(MqttRPCService.class.getName());

	public MqttRPCService() {
		logger.log(Level.INFO, "+++Constructor MqttRPCService v3");
		// to be used with @Activate
	}

	public MqttRPCService(GeckoMqttClient mqtt) {
		logger.log(Level.INFO, "+++ Constructor MqttRPCService v3");
		this.mqtt = mqtt;
	}

	@Override
	@Activate
	public void doActivate(MqttConfig config) {
		logger.log(Level.INFO, "+++Activate MqttRPCService v3");
		super.doActivate(config);
	}
	
	@Override
	protected GeckoMqttClient createClient(MqttConfig config, String id) {
		return new PahoV3Client(config, id);
	}

}