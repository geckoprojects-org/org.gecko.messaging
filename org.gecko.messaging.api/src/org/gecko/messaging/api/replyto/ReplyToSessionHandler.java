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

import java.util.function.Supplier;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContextBuilder;
import org.osgi.annotation.versioning.ConsumerType;
import org.osgi.util.pushstream.PushStream;

/**
 * Whiteboard service that answers a request to open a session ({@link ReplyToPublisher#openSession})
 * with a duplex session: it receives the messages of the requester as a stream and returns the
 * stream of messages it sends. Bound like a {@link ReplyToSingleSubscriptionHandler}, with the
 * service property {@link ReplyToSubscriptionConstants#REPLY_TO_SUBSCRIPTION_CHANNEL_PROPERTY}.
 * <p>
 * The whiteboard starts publishing the returned stream when the requester is ready to receive,
 * on a worker thread of the instance, and ends the session when the stream ends. A synchronous
 * source (one that pushes all its messages in the thread that connects it, such as
 * {@code PushStreamProvider.streamOf}) occupies that worker for the lifetime of the session; a
 * source fed from elsewhere (a {@code SimplePushEventSource}, a capture callback) does not. When the requester closes the session or its lease
 * expires, the stream of its messages ends and the returned stream is closed.
 * <p>
 * When the returned stream fails, the requester learns only that the responder failed, never the
 * exception's text. To end a session with a reason meant for the requester (an error code, a
 * last message describing it), let the stream fail with a {@link SessionEndedException}: its last
 * message and reason reach the requester, in that order.
 */
@ConsumerType
public interface ReplyToSessionHandler {

	/**
	 * Opens a session.
	 *
	 * @param openRequest the request that opens the session, with its payload and headers
	 * @param messages the messages of the requester; connect it before returning, messages
	 *            arriving earlier are not kept
	 * @param responseBuilders supplies a builder per message to the requester, each already set to
	 *            its channel and the session id
	 * @return the stream of messages to the requester, must not be {@code null}
	 */
	PushStream<Message> handleSession(Message openRequest, PushStream<Message> messages,
			Supplier<MessageContextBuilder> responseBuilders);

}
