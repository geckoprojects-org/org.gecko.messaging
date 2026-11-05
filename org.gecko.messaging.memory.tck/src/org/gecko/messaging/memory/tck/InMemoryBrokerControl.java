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
package org.gecko.messaging.memory.tck;

import org.gecko.messaging.core.memory.InMemoryBroker;
import org.gecko.messaging.tck.spi.BrokerControl;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;

/**
 * Broker control of the TCK run: the in-memory broker named by the framework property
 * {@code org.gecko.messaging.memory.tck.broker}, default {@code tck}.
 */
@Component
public class InMemoryBrokerControl implements BrokerControl {

	private InMemoryBroker broker;

	@Activate
	void activate(BundleContext context) {
		String name = context.getProperty("org.gecko.messaging.memory.tck.broker");
		broker = InMemoryBroker.get(name == null ? "tck" : name);
	}

	@Override
	public void dropConnections() {
		broker.dropConnections();
	}

	@Override
	public void setAvailable(boolean available) {
		broker.setAvailable(available);
	}

}
