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
package org.gecko.messaging.mqtt3.tck;

import java.io.IOException;

import org.gecko.messaging.tck.spi.BrokerControl;
import org.gecko.messaging.tck.spi.TcpProxy;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;

/**
 * Broker control of the MQTT 3.1.1 TCK run: the instances connect through a proxy on port 11886 to
 * Mosquitto on port 11883 (docker/brokers.sh).
 */
@Component(immediate = true, service = BrokerControl.class)
public class Mqtt3BrokerControl extends TcpProxy {

	@Activate
	void activate(BundleContext context) throws IOException {
		start(context, 11886, "localhost:11883");
	}

	@Deactivate
	void deactivate() {
		stop();
	}

}
