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
import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.MessagingConstants;
import org.osgi.annotation.versioning.ProviderType;
import org.osgi.util.promise.Promise;
import org.osgi.util.pushstream.PushStream;

/**
 * Publishes a request and receives the response(s) — the requesting side of reply-to messaging.
 * <p>
 * The implementation subscribes to the reply-to channel <em>before</em> it publishes the request,
 * accepts only responses whose correlation id is that of the request, and unsubscribes when the
 * exchange is over. A request without correlation id gets a generated one if the instance
 * supports {@link MessagingConstants#FEATURE_GENERATE_CORRELATION_ID}; a request without reply-to
 * channel a generated, temporary one if it supports
 * {@link MessagingConstants#FEATURE_GENERATE_REPLY_CHANNEL}. Otherwise both are required.
 * <p>
 * Registered by instances that support {@link MessagingConstants#FEATURE_REPLY_TO}.
 */
@ProviderType
public interface ReplyToPublisher {

	/**
	 * Publishes the request and resolves with the first response.
	 *
	 * @param requestMessage the request, channel and reply-to taken from its context
	 * @return a promise resolved with the response; failed with a
	 *         {@link java.util.concurrent.TimeoutException} if no response arrived within the
	 *         {@link MessagingConstants#EXTENSION_REPLY_TIMEOUT reply timeout}
	 */
	Promise<Message> publishWithReply(Message requestMessage);

	/**
	 * Publishes the request with the given context and resolves with the first response.
	 *
	 * @param requestMessage the request, only its payload is used
	 * @param context the context of the request, replacing that of the message
	 * @return a promise resolved with the response
	 * @see #publishWithReply(Message)
	 */
	Promise<Message> publishWithReply(Message requestMessage, MessageContext context);

	/**
	 * Publishes the request and streams the responses. The stream ends with the response that
	 * carries the header {@link MessagingConstants#HEADER_REPLY_COMPLETE} (that response itself is
	 * not part of the stream) or when the consumer closes it. It fails with a
	 * {@link java.util.concurrent.TimeoutException} when no response arrived within the reply
	 * timeout since the request or the previous response. Requires
	 * {@link MessagingConstants#FEATURE_REPLY_TO_MANY}.
	 *
	 * @param requestMessage the request, channel and reply-to taken from its context
	 * @return the stream of responses; the request is published when the stream is connected
	 */
	PushStream<Message> publishWithReplyMany(Message requestMessage);

	/**
	 * Publishes the request with the given context and streams the responses.
	 *
	 * @param requestMessage the request, only its payload is used
	 * @param context the context of the request, replacing that of the message
	 * @return the stream of responses
	 * @see #publishWithReplyMany(Message)
	 */
	PushStream<Message> publishWithReplyMany(Message requestMessage, MessageContext context);

}
