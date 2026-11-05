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

import org.gecko.messaging.api.dto.MessagingRuntimeDTO;
import org.osgi.annotation.versioning.ProviderType;

/**
 * Introspection of a messaging instance. Every instance registers one runtime service, with the
 * same instance properties as its other services.
 */
@ProviderType
public interface MessageServiceRuntime {

	/**
	 * Returns a snapshot of the state of the instance.
	 *
	 * @return the runtime DTO
	 */
	MessagingRuntimeDTO getRuntimeDTO();

	/**
	 * Returns the current connection state.
	 *
	 * @return the connection state
	 */
	ConnectionState getConnectionState();

}
