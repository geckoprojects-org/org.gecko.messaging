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

import java.util.function.Function;

import org.osgi.annotation.versioning.ProviderType;
import org.osgi.util.promise.Promise;

/**
 * Publishes messages to channels.
 * <p>
 * Publishing is asynchronous. The returned promise resolves when the message is handed over: for
 * a quality of service of at least once, or with publisher confirms, when the broker confirmed
 * it; otherwise when it was written to the connection. It fails with a {@link MessagingException}
 * if the message could not be sent, with a {@link FeatureNotSupportedException} if the context
 * asks for a feature the instance does not support, and while the instance is not connected —
 * unless the instance is configured to buffer messages for a reconnect.
 */
@ProviderType
public interface MessagePublisher {

	/**
	 * Publishes the message to the channel of its context.
	 *
	 * @param message the message
	 * @return a promise resolved when the message is handed over
	 */
	Promise<Void> publish(Message message);

	/**
	 * Publishes the message to the given channel. All other values come from the context of the
	 * message.
	 *
	 * @param message the message
	 * @param channel the channel to publish to
	 * @return a promise resolved when the message is handed over
	 */
	Promise<Void> publish(Message message, String channel);

	/**
	 * Publishes the payload of the message with the given context. The given context replaces the
	 * context of the message.
	 *
	 * @param message the message, only its payload is used
	 * @param context the context to publish with
	 * @return a promise resolved when the message is handed over
	 */
	Promise<Void> publish(Message message, MessageContext context);

	/**
	 * Publishes the message the function builds with a fresh builder of this instance.
	 *
	 * @param builderFunction creates the message from the builder
	 * @return a promise resolved when the message is handed over
	 */
	Promise<Void> publish(Function<MessageContextBuilder, Message> builderFunction);

}
