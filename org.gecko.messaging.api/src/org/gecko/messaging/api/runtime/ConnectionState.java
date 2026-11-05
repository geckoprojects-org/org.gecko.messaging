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
package org.gecko.messaging.api.runtime;

/**
 * The state of the connection of a messaging instance.
 */
public enum ConnectionState {

	/** The first connection is being established. */
	CONNECTING,

	/** Connected. */
	CONNECTED,

	/** The connection is lost; the instance is reconnecting. */
	DISCONNECTED,

	/** The instance gave up or was closed; it does not reconnect any more. */
	CLOSED

}
