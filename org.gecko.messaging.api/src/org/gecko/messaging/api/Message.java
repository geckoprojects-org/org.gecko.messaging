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
package org.gecko.messaging.api;

import java.nio.ByteBuffer;

import org.osgi.annotation.versioning.ProviderType;

/**
 * A message: the payload and the {@link MessageContext} that describes it.
 * <p>
 * Received messages are created by the implementation. Messages to send are created with a
 * {@link MessageContextBuilder}. Messages are immutable.
 */
@ProviderType
public interface Message {

	/**
	 * Returns the payload of this message.
	 * <p>
	 * Every call returns a new read-only buffer over the same content, positioned at the start of
	 * the payload. Consumers can therefore read it independently of each other.
	 *
	 * @return the payload, never {@code null}; an empty buffer for a message without payload
	 */
	ByteBuffer payload();

	/**
	 * Returns the context of this message.
	 *
	 * @return the message context, never {@code null}
	 */
	MessageContext getContext();

}
