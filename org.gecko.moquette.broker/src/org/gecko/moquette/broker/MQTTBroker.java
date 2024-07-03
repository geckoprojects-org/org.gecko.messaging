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
package org.gecko.moquette.broker;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;

import io.moquette.broker.Server;
import io.moquette.broker.config.IConfig;
import io.moquette.broker.config.MemoryConfig;
import io.moquette.broker.security.IAuthenticator;

/**
 * Service to setup Moquette MQTT Broker 
 * 
 * @author grune
 * @since Jul 3, 2024
 */
@Component(service = MQTTBroker.class, configurationPid = "MQTTBroker")
public class MQTTBroker {

	public static final String HOST = "HOST";
	public static final String PORT = "PORT";
	public static final String USERNAME = "USERNAME";
	public static final String PASSWORD = "PASSWORD";
	private Server server;
	private MemoryConfig config;
	private String validUsername;
	private String validPassword;

	@Activate
	public void activate(Map<String, String> cfg) throws IOException {
		server = new Server();
		config = new MemoryConfig(new Properties());
		config.setProperty(IConfig.HOST_PROPERTY_NAME, cfg.get(HOST));
		config.setProperty(IConfig.PORT_PROPERTY_NAME, cfg.get(PORT));
		if (cfg.containsKey(USERNAME) && cfg.containsKey(PASSWORD)) {
			validUsername = cfg.get(USERNAME);
			validPassword = cfg.get(PASSWORD);
			config.setProperty(IConfig.AUTHENTICATOR_CLASS_NAME, TestAuthenticator.class.getName());
		}
		server.startServer(config);
	}

	@Deactivate
	public void deactivate() {
		server.stopServer();
	}

	public void start() throws IOException {
		server.startServer(config);
	}

	public void stop() {
		server.stopServer();
	}

	class TestAuthenticator implements IAuthenticator {

		@Override
		public boolean checkValid(String clientId, String username, byte[] password) {
			return validUsername.equals(username) && validPassword.getBytes().equals(password);
		}

	}
}
