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
package org.gecko.messaging.core;

import static java.util.Objects.requireNonNull;

import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.gecko.messaging.api.FeatureNotSupportedException;
import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.MessageContextBuilder;
import org.gecko.messaging.api.MessagingConstants;
import org.gecko.messaging.api.MessagingException;
import org.gecko.messaging.api.acknowledge.AcknowledgeMessageContext;
import org.gecko.messaging.api.acknowledge.AcknowledgeMessageContextBuilder;
import org.gecko.messaging.api.cloudevents.CloudEvent;
import org.gecko.messaging.api.cloudevents.CloudEventMessageContext;
import org.gecko.messaging.api.cloudevents.CloudEventMessageContextBuilder;

/**
 * The builder of all implementations. It adapts to {@link AcknowledgeMessageContextBuilder} and
 * {@link CloudEventMessageContextBuilder} when the instance supports the corresponding feature.
 * Not thread safe.
 */
public class SimpleMessageContextBuilder
		implements MessageContextBuilder, AcknowledgeMessageContextBuilder, CloudEventMessageContextBuilder {

	private final Set<String> features;
	private ContextData data = new ContextData();
	private ByteBuffer content;

	/**
	 * Creates a builder that adapts to every specialized builder.
	 */
	public SimpleMessageContextBuilder() {
		this(null);
	}

	/**
	 * Creates a builder for an instance with the given features.
	 *
	 * @param features the features of the instance; {@code null} allows all
	 */
	public SimpleMessageContextBuilder(Set<String> features) {
		this.features = features;
	}

	private void reset() {
		data = new ContextData();
		content = null;
	}

	@Override
	public MessageContextBuilder withContext(MessageContext context) {
		requireNonNull(context, "context");
		ContextData d = new ContextData();
		d.channel = context.getChannel();
		d.channelExtension = context.getChannelExtension();
		d.contentType = context.getContentType();
		d.contentEncoding = context.getContentEncoding();
		d.correlationId = context.getCorrelationId();
		d.replyToChannel = context.getReplyToChannel();
		d.headers.putAll(context.getHeaders());
		d.extensions.putAll(context.getExtensions());
		if (context instanceof AcknowledgeMessageContext ack) {
			d.acknowledgeFilter = ack.getAcknowledgeFilter();
			d.acknowledgeFunction = ack.getAcknowledgeFunction();
			d.manualAcknowledge = ack.isManualAcknowledge();
			d.requeueOnReject = ack.isRequeueOnReject();
			d.postAcknowledge = ack.getPostAcknowledge();
			d.postReject = ack.getPostReject();
			d.acknowledgeConfigured = d.acknowledgeFilter != null || d.acknowledgeFunction != null || d.manualAcknowledge
					|| d.postAcknowledge != null || d.postReject != null;
		}
		if (context instanceof CloudEventMessageContext ce) {
			copyCloudEvent(d, ce.getCloudEvent());
		}
		data = d;
		return this;
	}

	private static void copyCloudEvent(ContextData d, CloudEvent ce) {
		d.cloudEvent = true;
		d.ceId = ce.getId();
		d.ceSource = ce.getSource();
		d.ceType = ce.getType();
		d.ceSubject = ce.getSubject();
		d.ceDataSchema = ce.getDataSchema();
		d.ceTime = ce.getTime();
		d.headers.putAll(ce.getExtensions());
	}

	@Override
	public MessageContextBuilder content(ByteBuffer content) {
		this.content = content == null ? null : content.duplicate();
		return this;
	}

	@Override
	public <T> MessageContextBuilder content(T object, Function<T, ByteBuffer> contentMapper) {
		requireNonNull(contentMapper, "contentMapper");
		return content(contentMapper.apply(object));
	}

	@Override
	public MessageContextBuilder channel(String channelName) {
		data.channel = channelName;
		return this;
	}

	@Override
	public MessageContextBuilder channel(String channelName, String channelExtension) {
		data.channel = channelName;
		data.channelExtension = channelExtension;
		return this;
	}

	@Override
	public MessageContextBuilder replyTo(String replyToChannel) {
		data.replyToChannel = replyToChannel;
		return this;
	}

	@Override
	public MessageContextBuilder correlationId(String correlationId) {
		data.correlationId = correlationId;
		return this;
	}

	@Override
	public MessageContextBuilder contentType(String contentType) {
		data.contentType = contentType;
		return this;
	}

	@Override
	public MessageContextBuilder contentEncoding(String contentEncoding) {
		data.contentEncoding = contentEncoding;
		return this;
	}

	@Override
	public MessageContextBuilder header(String key, Object value) {
		requireNonNull(key, "key");
		if (value == null) {
			data.headers.remove(key);
		} else if (value instanceof String || value instanceof Number || value instanceof Boolean) {
			data.headers.put(key, value);
		} else {
			throw new IllegalArgumentException("Header '" + key + "' must be a String, Number or Boolean, not " + value.getClass().getName());
		}
		return this;
	}

	@Override
	public MessageContextBuilder headers(Map<String, ?> headers) {
		if (headers != null) {
			headers.forEach(this::header);
		}
		return this;
	}

	@Override
	public MessageContextBuilder extensionEntry(String key, Object value) {
		requireNonNull(key, "key");
		if (value == null) {
			data.extensions.remove(key);
		} else {
			data.extensions.put(key, value);
		}
		return this;
	}

	@Override
	public MessageContextBuilder extensions(Map<String, ?> extensions) {
		if (extensions != null) {
			extensions.forEach(this::extensionEntry);
		}
		return this;
	}

	@Override
	public MessageContext buildContext() {
		try {
			return createContext(data);
		} finally {
			reset();
		}
	}

	@Override
	public Message buildMessage() {
		try {
			return new SimpleMessage(content, createContext(data));
		} finally {
			reset();
		}
	}

	private static SimpleMessageContext createContext(ContextData d) {
		if (!d.cloudEvent) {
			return new SimpleMessageContext(d);
		}
		if (d.ceType == null || d.ceSource == null) {
			throw new MessagingException("A CloudEvent needs a type and a source");
		}
		String id = d.ceId == null ? UUID.randomUUID().toString() : d.ceId;
		OffsetDateTime time = d.ceTime == null ? OffsetDateTime.now() : d.ceTime;
		SimpleCloudEvent ce = new SimpleCloudEvent(id, d.ceSource, d.ceType, d.ceDataSchema, d.ceSubject, time, d.headers);
		return new CloudEventContext(d, ce);
	}

	@Override
	public boolean isAdaptable(Class<?> type) {
		if (type == null) {
			return false;
		}
		if (type == AcknowledgeMessageContextBuilder.class) {
			return supports(MessagingConstants.FEATURE_ACKNOWLEDGE);
		}
		if (type == CloudEventMessageContextBuilder.class) {
			return supports(MessagingConstants.FEATURE_CLOUD_EVENTS);
		}
		// only the API's adaptations: implementation types are not part of the contract
		return false;
	}

	private boolean supports(String feature) {
		return features == null || features.contains(feature);
	}

	@Override
	public <A> A adapt(Class<A> type) {
		if (!isAdaptable(type)) {
			String feature = type == AcknowledgeMessageContextBuilder.class ? MessagingConstants.FEATURE_ACKNOWLEDGE
					: type == CloudEventMessageContextBuilder.class ? MessagingConstants.FEATURE_CLOUD_EVENTS
					: String.valueOf(type == null ? null : type.getName());
			throw new FeatureNotSupportedException(feature, "This builder cannot be adapted to " + type);
		}
		return type.cast(this);
	}

	// --- AcknowledgeMessageContextBuilder

	@Override
	public AcknowledgeMessageContextBuilder acknowledgeFilter(Predicate<Message> filter) {
		data.acknowledgeFilter = filter;
		data.acknowledgeConfigured = true;
		return this;
	}

	@Override
	public AcknowledgeMessageContextBuilder acknowledgeFunction(Consumer<Message> function) {
		data.acknowledgeFunction = function;
		data.acknowledgeConfigured = true;
		return this;
	}

	@Override
	public AcknowledgeMessageContextBuilder manualAcknowledge() {
		data.manualAcknowledge = true;
		data.acknowledgeConfigured = true;
		return this;
	}

	@Override
	public AcknowledgeMessageContextBuilder requeueOnReject(boolean requeue) {
		data.requeueOnReject = requeue;
		data.acknowledgeConfigured = true;
		return this;
	}

	@Override
	public AcknowledgeMessageContextBuilder postAcknowledge(Consumer<Message> consumer) {
		data.postAcknowledge = consumer;
		data.acknowledgeConfigured = true;
		return this;
	}

	@Override
	public AcknowledgeMessageContextBuilder postReject(Consumer<Message> consumer) {
		data.postReject = consumer;
		data.acknowledgeConfigured = true;
		return this;
	}

	@Override
	public MessageContextBuilder messageContextBuilder() {
		return this;
	}

	// --- CloudEventMessageContextBuilder

	@Override
	public CloudEventMessageContextBuilder id(String id) {
		data.cloudEvent = true;
		data.ceId = id;
		return this;
	}

	@Override
	public CloudEventMessageContextBuilder source(String source) {
		data.cloudEvent = true;
		data.ceSource = source;
		return this;
	}

	@Override
	public CloudEventMessageContextBuilder type(String type) {
		data.cloudEvent = true;
		data.ceType = type;
		return this;
	}

	@Override
	public CloudEventMessageContextBuilder subject(String subject) {
		data.cloudEvent = true;
		data.ceSubject = subject;
		return this;
	}

	@Override
	public CloudEventMessageContextBuilder dataSchema(String dataSchema) {
		data.cloudEvent = true;
		data.ceDataSchema = dataSchema;
		return this;
	}

	@Override
	public CloudEventMessageContextBuilder time(OffsetDateTime time) {
		data.cloudEvent = true;
		data.ceTime = time;
		return this;
	}

	@Override
	public CloudEventMessageContextBuilder withCloudEvent(CloudEvent cloudEvent) {
		copyCloudEvent(data, requireNonNull(cloudEvent, "cloudEvent"));
		return this;
	}

	/**
	 * Builds a received CloudEvent message context. For protocol implementations.
	 *
	 * @param context the plain context of the received message
	 * @param cloudEvent the decoded CloudEvent attributes
	 * @return the CloudEvent context
	 */
	public static SimpleMessageContext cloudEventContext(MessageContext context, CloudEvent cloudEvent) {
		SimpleMessageContextBuilder b = new SimpleMessageContextBuilder();
		b.withContext(context);
		b.withCloudEvent(cloudEvent);
		return createContext(b.data);
	}

	/**
	 * Returns the given context as {@link SimpleMessageContext}, copying it if it is foreign.
	 *
	 * @param context the context
	 * @return the simple context
	 */
	public static SimpleMessageContext toSimple(MessageContext context) {
		if (context instanceof SimpleMessageContext simple) {
			return simple;
		}
		SimpleMessageContextBuilder b = new SimpleMessageContextBuilder();
		b.withContext(context);
		return createContext(b.data);
	}

}
