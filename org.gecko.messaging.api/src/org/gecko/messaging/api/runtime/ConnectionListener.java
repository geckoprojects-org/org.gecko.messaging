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

import org.osgi.annotation.versioning.ConsumerType;

/**
 * Whiteboard service notified about connection changes of messaging instances.
 * <p>
 * The service property {@link org.gecko.messaging.api.MessagingConstants#MESSAGING_TARGET_PROPERTY}
 * selects the instances; without it the listener hears all instances. A listener registered
 * while an instance is in a state is told that state at once (with {@code previousState}
 * {@code null}). Events of one instance are delivered in order, on a thread of the implementation;
 * a listener must not block.
 */
@ConsumerType
public interface ConnectionListener {

	/**
	 * Called when the connection state of a messaging instance changed.
	 *
	 * @param event the change
	 */
	void connectionChanged(ConnectionEvent event);

}
