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
package org.gecko.messaging.api.replyto;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessagingConstants;
import org.gecko.messaging.api.MessagingException;

/**
 * The end of a duplex session with a reason the application gives, kept apart from the failures
 * of the library.
 * <p>
 * <b>Responder:</b> the stream a {@link ReplyToSessionHandler} returns fails with this exception
 * to end the session with its reason. The whiteboard publishes the given last message (or an
 * empty one) to the requester with the header {@link MessagingConstants#HEADER_SESSION_REASON}
 * and ends the session. Only the reason and the last message travel: neither the exception's
 * text nor its cause. Any other failure of the stream still reaches the requester as the fixed
 * {@link MessagingConstants#HEADER_REPLY_ERROR}.
 * <p>
 * <b>Requester:</b> {@link MessagingSession#receive()} delivers that last message as its last
 * element and then fails with this exception; {@link MessagingSession#closed()} fails with it as
 * well. {@link #getReason()} is the responder's reason, {@link #getLastMessage()} the message.
 */
public class SessionEndedException extends MessagingException {

	private static final long serialVersionUID = 1L;

	private final String reason;
	private final transient Message lastMessage;

	/**
	 * Creates the exception with which a responder ends a session.
	 *
	 * @param reason the reason for the requester, e.g. an error code; short and without internals
	 * @param lastMessage the message the requester receives last, built from the session's response
	 *            builders; {@code null} for an empty one
	 */
	public SessionEndedException(String reason, Message lastMessage) {
		super("The session ended: " + reason);
		if (reason == null || reason.isEmpty()) {
			throw new IllegalArgumentException("reason");
		}
		this.reason = reason;
		this.lastMessage = lastMessage;
	}

	/**
	 * Creates the exception with which a responder ends a session, without a last message of its own.
	 *
	 * @param reason the reason for the requester
	 */
	public SessionEndedException(String reason) {
		this(reason, null);
	}

	/** @return the reason the responder gave */
	public String getReason() {
		return reason;
	}

	/** @return the last message of the session, {@code null} if none is known */
	public Message getLastMessage() {
		return lastMessage;
	}

}
