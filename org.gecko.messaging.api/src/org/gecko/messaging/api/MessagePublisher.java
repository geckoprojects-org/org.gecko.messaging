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
import org.osgi.util.pushstream.PushStream;

/**
 * Publishes messages to channels.
 * <p>
 * Publishing is asynchronous. The returned promise resolves when the message is handed over: for
 * a quality of service of at least once, or with publisher confirms, when the broker confirmed
 * it; otherwise when it was written to the connection. It fails with a {@link MessagingException}
 * if the message could not be sent, with a {@link FeatureNotSupportedException} if the context
 * asks for a feature the instance does not support, and while the instance is not connected:
 * messages are not buffered for a reconnect. Failures are reported through the promise, never
 * thrown.
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

	/**
	 * Publishes every message of a stream, in its order, each to the channel of its context.
	 * <p>
	 * The instance takes the next message only while its send window has room (outstanding
	 * broker confirmations, writes to the connection): it holds back the thread delivering the
	 * stream, never a thread of the protocol. The promise resolves with the number of messages
	 * once the stream has ended and every message is handed over. It fails with the first failure
	 * of a message, or with the failure of the stream; the stream is closed then. Closing the
	 * stream ends the publishing.
	 *
	 * @param messages the messages
	 * @return a promise resolved with the number of messages handed over
	 */
	Promise<Long> publish(PushStream<? extends Message> messages);

	/**
	 * Publishes the payloads of every message of a stream with the given context, in the order of
	 * the stream; as {@link #publish(PushStream)} otherwise.
	 *
	 * @param messages the messages, only their payloads are used
	 * @param context the context to publish every message with
	 * @return a promise resolved with the number of messages handed over
	 */
	Promise<Long> publish(PushStream<? extends Message> messages, MessageContext context);

}
