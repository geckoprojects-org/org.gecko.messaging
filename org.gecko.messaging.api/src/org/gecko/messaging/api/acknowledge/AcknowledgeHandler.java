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
package org.gecko.messaging.api.acknowledge;

import org.osgi.annotation.versioning.ProviderType;

/**
 * Acknowledges or rejects one received message. Only the first call of either method has an
 * effect.
 */
@ProviderType
public interface AcknowledgeHandler {

	/**
	 * Acknowledges the message.
	 *
	 * @return {@code true} if the message was acknowledged by this call, {@code false} if it was
	 *         acknowledged or rejected before
	 */
	boolean acknowledge();

	/**
	 * Rejects the message.
	 *
	 * @return {@code true} if the message was rejected by this call, {@code false} if it was
	 *         acknowledged or rejected before
	 */
	boolean reject();

	/**
	 * Returns the current state.
	 *
	 * @return the acknowledgement state
	 */
	AcknowledgeType getAcknowledgeState();

}
