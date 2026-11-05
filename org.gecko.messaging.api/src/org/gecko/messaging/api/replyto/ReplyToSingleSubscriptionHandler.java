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
import org.gecko.messaging.api.MessageContextBuilder;
import org.osgi.annotation.versioning.ConsumerType;

/**
 * Whiteboard service that answers each request with one response — the responding side of
 * reply-to messaging.
 * <p>
 * The whiteboard of every messaging instance selected by the service property
 * {@link org.gecko.messaging.api.MessagingConstants#MESSAGING_TARGET_PROPERTY} subscribes to the
 * channel in {@link ReplyToSubscriptionConstants#REPLY_TO_SUBSCRIPTION_CHANNEL_PROPERTY}, calls
 * the handler per request and publishes the response to the request's reply-to channel with the
 * request's correlation id. A handler without target property is not bound.
 *
 * @see org.gecko.messaging.api.propertytypes.ReplyToSubscription
 */
@ConsumerType
public interface ReplyToSingleSubscriptionHandler {

	/**
	 * Creates the response to a request.
	 *
	 * @param requestMessage the request
	 * @param responseBuilder a builder already set to the reply-to channel and the correlation id
	 *            of the request
	 * @return the response; {@code null} sends no response
	 */
	Message handleResponse(Message requestMessage, MessageContextBuilder responseBuilder);

}
