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

import static java.util.Collections.unmodifiableMap;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.acknowledge.AcknowledgeHandler;
import org.gecko.messaging.api.acknowledge.AcknowledgeMessageContext;
import org.gecko.messaging.api.acknowledge.AcknowledgeType;

/**
 * Immutable message context, with the acknowledgement configuration of a subscription or the
 * acknowledgement handler of a received message.
 */
public class SimpleMessageContext implements AcknowledgeMessageContext {

	private final String channel;
	private final String channelExtension;
	private final String contentType;
	private final String contentEncoding;
	private final String correlationId;
	private final String replyToChannel;
	private final Map<String, Object> headers;
	private final Map<String, Object> extensions;
	private final Predicate<Message> acknowledgeFilter;
	private final Consumer<Message> acknowledgeFunction;
	private final boolean manualAcknowledge;
	private final boolean requeueOnReject;
	private final Consumer<Message> postAcknowledge;
	private final Consumer<Message> postReject;
	private final AcknowledgeHandler acknowledgeHandler;

	SimpleMessageContext(ContextData data) {
		this.channel = data.channel;
		this.channelExtension = data.channelExtension;
		this.contentType = data.contentType;
		this.contentEncoding = data.contentEncoding;
		this.correlationId = data.correlationId;
		this.replyToChannel = data.replyToChannel;
		this.headers = unmodifiableMap(new LinkedHashMap<>(data.headers));
		this.extensions = unmodifiableMap(new LinkedHashMap<>(data.extensions));
		this.acknowledgeFilter = data.acknowledgeFilter;
		this.acknowledgeFunction = data.acknowledgeFunction;
		this.manualAcknowledge = data.manualAcknowledge;
		this.requeueOnReject = data.requeueOnReject;
		this.postAcknowledge = data.postAcknowledge;
		this.postReject = data.postReject;
		this.acknowledgeHandler = null;
	}

	/**
	 * Copy constructor that attaches an acknowledgement handler.
	 *
	 * @param source the context to copy
	 * @param handler the handler of the received message
	 */
	protected SimpleMessageContext(SimpleMessageContext source, AcknowledgeHandler handler) {
		this.channel = source.channel;
		this.channelExtension = source.channelExtension;
		this.contentType = source.contentType;
		this.contentEncoding = source.contentEncoding;
		this.correlationId = source.correlationId;
		this.replyToChannel = source.replyToChannel;
		this.headers = source.headers;
		this.extensions = source.extensions;
		this.acknowledgeFilter = source.acknowledgeFilter;
		this.acknowledgeFunction = source.acknowledgeFunction;
		this.manualAcknowledge = source.manualAcknowledge;
		this.requeueOnReject = source.requeueOnReject;
		this.postAcknowledge = source.postAcknowledge;
		this.postReject = source.postReject;
		this.acknowledgeHandler = handler;
	}

	/**
	 * Returns a copy of this context with the given acknowledgement handler.
	 *
	 * @param handler the handler
	 * @return the new context
	 */
	public SimpleMessageContext withAcknowledgeHandler(AcknowledgeHandler handler) {
		return new SimpleMessageContext(this, handler);
	}

	@Override
	public String getChannel() {
		return channel;
	}

	@Override
	public String getChannelExtension() {
		return channelExtension;
	}

	@Override
	public String getContentType() {
		return contentType;
	}

	@Override
	public String getContentEncoding() {
		return contentEncoding;
	}

	@Override
	public String getCorrelationId() {
		return correlationId;
	}

	@Override
	public String getReplyToChannel() {
		return replyToChannel;
	}

	@Override
	public Map<String, Object> getHeaders() {
		return headers;
	}

	@Override
	public Map<String, Object> getExtensions() {
		return extensions;
	}

	@Override
	public AcknowledgeHandler getAcknowledgeHandler() {
		return acknowledgeHandler;
	}

	@Override
	public AcknowledgeType getAcknowledgeState() {
		return acknowledgeHandler == null ? AcknowledgeType.UNSUPPORTED : acknowledgeHandler.getAcknowledgeState();
	}

	@Override
	public Predicate<Message> getAcknowledgeFilter() {
		return acknowledgeFilter;
	}

	@Override
	public Consumer<Message> getAcknowledgeFunction() {
		return acknowledgeFunction;
	}

	@Override
	public boolean isManualAcknowledge() {
		return manualAcknowledge;
	}

	@Override
	public boolean isRequeueOnReject() {
		return requeueOnReject;
	}

	@Override
	public Consumer<Message> getPostAcknowledge() {
		return postAcknowledge;
	}

	@Override
	public Consumer<Message> getPostReject() {
		return postReject;
	}

	@Override
	public String toString() {
		return getClass().getSimpleName() + "[channel=" + channel + (channelExtension == null ? "" : "/" + channelExtension)
				+ (contentType == null ? "" : ", contentType=" + contentType)
				+ (correlationId == null ? "" : ", correlationId=" + correlationId)
				+ (replyToChannel == null ? "" : ", replyTo=" + replyToChannel) + (headers.isEmpty() ? "" : ", headers=" + headers)
				+ (extensions.isEmpty() ? "" : ", extensions=" + extensions) + "]";
	}

}
