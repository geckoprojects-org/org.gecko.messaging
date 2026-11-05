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
package org.gecko.messaging.core;

/**
 * The protocol side of acknowledging one received message.
 */
public interface Acknowledger {

	/** Acknowledges the message on the broker. */
	void acknowledge();

	/**
	 * Rejects the message on the broker.
	 *
	 * @param requeue {@code true} to re-queue it
	 */
	void reject(boolean requeue);

}
