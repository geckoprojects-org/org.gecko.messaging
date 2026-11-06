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
import org.osgi.annotation.versioning.ProviderType;
import org.osgi.util.promise.Promise;
import org.osgi.util.pushstream.PushStream;

/**
 * A duplex session with one responder, opened with {@link ReplyToPublisher#openSession}: a
 * stream of messages from the responder (for example video frames) and messages to it (for
 * example commands that reconfigure what it sends), for as long as the session lives.
 * <p>
 * The session lives while both sides hear from each other within its lease; each side sends a
 * ping when it has nothing else to send. It ends when either side closes it, when the lease
 * expires, or when the instance stops.
 */
@ProviderType
public interface MessagingSession extends AutoCloseable {

	/** @return the id of the session, the correlation id of all its messages */
	String getSessionId();

	/**
	 * Returns the stream of messages from the responder. The responder starts sending when the
	 * stream is connected, so nothing is missed before. The stream ends when the session ends:
	 * normally when it is closed, with an error when the lease expired or the responder failed.
	 * The buffer of the stream follows the extensions of the context the session was opened with
	 * ({@code osgi.messaging.bufferSize}, {@code queuePolicy}, {@code groupHeader}).
	 *
	 * @return the stream; the same one on every call
	 */
	PushStream<Message> receive();

	/**
	 * Sends a message to the responder.
	 *
	 * @param message the message, only payload, content type and headers are used
	 * @return a promise resolved when the message is handed over
	 */
	Promise<Void> send(Message message);

	/**
	 * Sends every message of a stream to the responder, in order, within the send window of the
	 * instance.
	 *
	 * @param messages the messages, only payload, content type and headers are used
	 * @return a promise resolved with the number of messages handed over
	 */
	Promise<Long> send(PushStream<? extends Message> messages);

	/**
	 * @return a promise resolved when the session ended normally, failed when its lease expired
	 *         or the responder failed
	 */
	Promise<Void> closed();

	/** Ends the session; the responder is told. */
	@Override
	void close();

}
