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

import java.util.Map;

import org.osgi.annotation.versioning.ProviderType;

/**
 * The meta data of a message — comparable to the header of an HTTP request.
 * <p>
 * On the receiving side the implementation creates the context from what the protocol delivered.
 * On the sending side it describes how a message is to be sent; on a subscription it describes how
 * to subscribe. Values a protocol cannot carry are {@code null}.
 * <p>
 * Contexts are immutable; all maps returned are unmodifiable.
 */
@ProviderType
public interface MessageContext {

	/**
	 * Returns the channel: a topic, a queue or an exchange, depending on the protocol.
	 *
	 * @return the channel name, or {@code null} if not set
	 */
	String getChannel();

	/**
	 * Returns the channel extension, for example the AMQP routing key.
	 *
	 * @return the channel extension, or {@code null} if not set
	 */
	String getChannelExtension();

	/**
	 * Returns the content type of the payload, for example a mime type.
	 *
	 * @return the content type, or {@code null} if not set
	 */
	String getContentType();

	/**
	 * Returns the content encoding of the payload.
	 *
	 * @return the content encoding, or {@code null} if not set
	 */
	String getContentEncoding();

	/**
	 * Returns the correlation id that assigns a response to its request.
	 *
	 * @return the correlation id, or {@code null} if not set
	 */
	String getCorrelationId();

	/**
	 * Returns the channel a response to this message is expected on.
	 *
	 * @return the reply-to channel, or {@code null} if not set
	 */
	String getReplyToChannel();

	/**
	 * Returns the application headers that travel with the message (MQTT 5 user properties, AMQP
	 * headers, CloudEvents extension attributes, ...).
	 * <p>
	 * Values are {@code String}, {@code Number} or {@code Boolean}. A protocol that carries strings
	 * only delivers every value as {@code String}.
	 *
	 * @return the headers, never {@code null}
	 */
	Map<String, Object> getHeaders();

	/**
	 * Returns the extensions: options for the implementation that do not travel as headers, like
	 * the quality of service. Standard keys are defined in {@link MessagingConstants} (prefix
	 * {@code osgi.messaging.}); other keys are implementation specific.
	 *
	 * @return the extensions, never {@code null}
	 */
	Map<String, Object> getExtensions();

}
