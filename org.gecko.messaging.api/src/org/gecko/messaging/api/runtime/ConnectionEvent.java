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

import java.util.Objects;

/**
 * A change of the connection state of a messaging instance.
 *
 * @param instanceName the name of the messaging instance
 * @param previousState the state before the change, {@code null} for the first event
 * @param state the new state
 * @param reconnect {@code true} if the instance is {@link ConnectionState#CONNECTED} again after
 *            it was connected before. Messages published while it was disconnected may be lost:
 *            consumers that depend on a complete stream re-synchronise their state now, unless
 *            {@code sessionPresent} says the broker kept the session
 * @param sessionPresent {@code true} if the broker resumed the previous session with its
 *            subscriptions and queued messages
 * @param timestamp the time of the change in milliseconds since the epoch
 * @param cause the reason of a {@link ConnectionState#DISCONNECTED} or
 *            {@link ConnectionState#CLOSED}, or {@code null}
 */
public record ConnectionEvent(String instanceName, ConnectionState previousState, ConnectionState state,
		boolean reconnect, boolean sessionPresent, long timestamp, Throwable cause) {

	/**
	 * Validates the event.
	 *
	 * @param instanceName the name of the messaging instance
	 * @param previousState the previous state
	 * @param state the new state
	 * @param reconnect whether this is a reconnect
	 * @param sessionPresent whether the session was resumed
	 * @param timestamp the time of the change
	 * @param cause the cause
	 */
	public ConnectionEvent {
		Objects.requireNonNull(instanceName, "instanceName");
		Objects.requireNonNull(state, "state");
	}

}
