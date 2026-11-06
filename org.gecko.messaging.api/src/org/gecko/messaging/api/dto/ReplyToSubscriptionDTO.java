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
import org.osgi.framework.dto.ServiceReferenceDTO;

/**
 * A reply-to handler bound by the whiteboard of a messaging instance.
 *
 * @NotThreadSafe
 */
public class ReplyToSubscriptionDTO extends DTO {

	/** The handler service. */
	public ServiceReferenceDTO handlerService;

	/** The channel requests arrive on. */
	public ChannelDTO requestChannel;

	/** The channel for requests without reply-to channel, or {@code null}. */
	public ChannelDTO replyChannel;

	/** {@code true} for a handler of streamed responses. */
	public boolean many;

	/** {@code true} for a handler of duplex sessions. */
	public boolean session;

	/** The sessions of a session handler open now. */
	public int openSessions;

	/** The number of requests handled. */
	public long handledRequests;

	/** The number of requests not handled, as their reply-to channel is not allowed. */
	public long refusedRequests;

}
