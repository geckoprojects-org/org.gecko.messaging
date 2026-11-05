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

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.function.Function;

import org.osgi.annotation.versioning.ProviderType;

/**
 * Builds a {@link Message} with its {@link MessageContext}, or a {@link MessageContext} alone.
 * <p>
 * Every messaging instance registers a builder service with
 * {@link org.osgi.framework.Constants#SCOPE_PROTOTYPE prototype} scope. A builder is not thread
 * safe; obtain one per user, for example with
 * {@code @Reference(scope = ReferenceScope.PROTOTYPE_REQUIRED)}, or use
 * {@link MessagePublisher#publish(Function)}, which hands a fresh builder to the function.
 * <p>
 * After {@link #buildContext()} or {@link #buildMessage()} returned, the builder is reset to its
 * initial state and can build the next message.
 * <p>
 * Implementations offer additional, typed builders through {@link #adapt(Class)}, for example
 * {@link org.gecko.messaging.api.acknowledge.AcknowledgeMessageContextBuilder} or
 * {@link org.gecko.messaging.api.cloudevents.CloudEventMessageContextBuilder}.
 */
@ProviderType
public interface MessageContextBuilder {

	/**
	 * Takes all values of the given context as the starting point. Values set before are replaced;
	 * values set afterwards override those of the context.
	 *
	 * @param context the context to start from, must not be {@code null}
	 * @return this builder
	 */
	MessageContextBuilder withContext(MessageContext context);

	/**
	 * Sets the payload. The builder takes the remaining content of the buffer (position to
	 * limit) without changing the buffer.
	 *
	 * @param content the payload
	 * @return this builder
	 */
	MessageContextBuilder content(ByteBuffer content);

	/**
	 * Sets a typed payload that is mapped to bytes with the given function.
	 *
	 * @param <T> the type of the content
	 * @param content the content
	 * @param contentMapper maps the content to the payload bytes
	 * @return this builder
	 */
	<T> MessageContextBuilder content(T content, Function<T, ByteBuffer> contentMapper);

	/**
	 * Sets the channel.
	 *
	 * @param channelName the channel, a topic or queue name
	 * @return this builder
	 */
	MessageContextBuilder channel(String channelName);

	/**
	 * Sets the channel and its extension, for example an exchange and a routing key.
	 *
	 * @param channelName the channel
	 * @param channelExtension the extension of the channel, like a routing key
	 * @return this builder
	 */
	MessageContextBuilder channel(String channelName, String channelExtension);

	/**
	 * Sets the channel a receiver is to send the response to.
	 *
	 * @param replyToChannel the reply channel
	 * @return this builder
	 */
	MessageContextBuilder replyTo(String replyToChannel);

	/**
	 * Sets the correlation id that assigns a response to its request.
	 *
	 * @param correlationId the correlation id
	 * @return this builder
	 */
	MessageContextBuilder correlationId(String correlationId);

	/**
	 * Sets the content type of the payload.
	 *
	 * @param contentType the content type, like a mime type
	 * @return this builder
	 */
	MessageContextBuilder contentType(String contentType);

	/**
	 * Sets the content encoding of the payload.
	 *
	 * @param contentEncoding the content encoding
	 * @return this builder
	 */
	MessageContextBuilder contentEncoding(String contentEncoding);

	/**
	 * Adds an application header. A {@code null} value removes the header.
	 *
	 * @param key the header name
	 * @param value a {@code String}, {@code Number} or {@code Boolean}
	 * @return this builder
	 * @see MessageContext#getHeaders()
	 */
	MessageContextBuilder header(String key, Object value);

	/**
	 * Adds all given application headers.
	 *
	 * @param headers the headers to add
	 * @return this builder
	 */
	MessageContextBuilder headers(Map<String, ?> headers);

	/**
	 * Adds an extension entry. A {@code null} value removes the entry.
	 *
	 * @param key the extension key, see {@link MessagingConstants} for the standard keys
	 * @param value the value
	 * @return this builder
	 * @see MessageContext#getExtensions()
	 */
	MessageContextBuilder extensionEntry(String key, Object value);

	/**
	 * Adds all given extension entries.
	 *
	 * @param extensions the extensions to add
	 * @return this builder
	 */
	MessageContextBuilder extensions(Map<String, ?> extensions);

	/**
	 * Builds the context and resets this builder.
	 *
	 * @return the message context
	 */
	MessageContext buildContext();

	/**
	 * Builds the message with its context and resets this builder.
	 *
	 * @return the message
	 */
	Message buildMessage();

	/**
	 * Returns whether this builder can be adapted to the given type.
	 *
	 * @param type the type of the specialized builder
	 * @return {@code true} if {@link #adapt(Class)} returns an instance for the type
	 */
	boolean isAdaptable(Class<?> type);

	/**
	 * Adapts this builder to a specialized builder. The adapted builder shares the state of this
	 * builder: values set on either are part of the built message.
	 *
	 * @param <A> the type of the specialized builder
	 * @param type the type of the specialized builder
	 * @return the adapted builder
	 * @throws FeatureNotSupportedException if this builder cannot be adapted to the type
	 */
	<A> A adapt(Class<A> type);

}
