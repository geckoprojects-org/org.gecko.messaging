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
package org.gecko.messaging.tck.spi;

import org.osgi.annotation.versioning.ConsumerType;

/**
 * Lets the TCK break the connection of the instance under test — a broker restart, a dead mobile
 * link. Registered by a TCK run that can do it, for example through a TCP proxy between the
 * instance and the broker. Without this service the reconnect tests are skipped.
 */
@ConsumerType
public interface BrokerControl {

	/**
	 * Drops every connection of the instance under test now. New connections are accepted.
	 */
	void dropConnections();

	/**
	 * Makes the broker unreachable (connections dropped, new ones refused) or reachable again.
	 *
	 * @param available whether the broker is reachable
	 */
	void setAvailable(boolean available);

}
