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
 * Whiteboard service that answers each request with a stream of responses. Bound like a
 * {@link ReplyToSingleSubscriptionHandler}. The whiteboard publishes every message of the stream
 * and, when the stream ends, a last, empty response with the header
 * {@link org.gecko.messaging.api.MessagingConstants#HEADER_REPLY_COMPLETE}.
 */
@ConsumerType
public interface ReplyToManySubscriptionHandler {

	/**
	 * Creates the responses to a request.
	 *
	 * @param requestMessage the request
	 * @param responseBuilders supplies a builder per response, each already set to the reply-to
	 *            channel and the correlation id of the request
	 * @return the stream of responses, must not be {@code null}
	 */
	PushStream<Message> handleResponses(Message requestMessage, Supplier<MessageContextBuilder> responseBuilders);

}
