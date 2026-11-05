/**
 * Copyright (c) 2012 - 2026 Data In Motion and others.
 * All rights reserved.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
package org.gecko.messaging.websocket;

import java.util.Map;

import org.gecko.messaging.core.MessagingConfiguration;
import org.gecko.messaging.core.ProvideMessagingImplementation;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;

/**
 * One WebSocket messaging instance per factory configuration {@code org.gecko.messaging.websocket}.
 */
@ProvideMessagingImplementation(protocol = WebSocketInstance.PROTOCOL)
@Component(name = "org.gecko.messaging.websocket", configurationPolicy = ConfigurationPolicy.REQUIRE, service = {})
public class WebSocketMessagingComponent {

	private WebSocketInstance instance;

	@Activate
	void activate(BundleContext context, Map<String, Object> properties) {
		instance = new WebSocketInstance(new MessagingConfiguration(properties));
		instance.start(context);
	}

	@Deactivate
	void deactivate() {
		instance.stop();
	}

}
