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
package org.gecko.messaging.api.dto;

import org.osgi.dto.DTO;

/**
 * An active subscription of a messaging instance.
 *
 * @NotThreadSafe
 */
public class SubscriptionDTO extends DTO {

	/** The subscribed channel. */
	public ChannelDTO channel;

	/** The number of messages received on this subscription. */
	public long receivedMessages;

	/**
	 * The number of received messages dropped: undecodable, not a CloudEvent on an instance that
	 * takes CloudEvents only, or discarded by a full buffer ({@code DISCARD_OLDEST}).
	 */
	public long droppedMessages;

	/** The messages the buffer of the subscription holds at most. */
	public int bufferSize;

	/** The payload bytes the buffer of the subscription holds at most. */
	public long bufferBytes;

	/** The messages in the buffer now: received, not yet taken by the stream. */
	public int bufferedMessages;

	/** The payload bytes in the buffer now. */
	public long bufferedBytes;

	/**
	 * The time a full buffer held back delivery so far, in milliseconds ({@code BLOCK}); without
	 * broker flow control this time is spent on a thread of the protocol.
	 */
	public long blockedMillis;

}
