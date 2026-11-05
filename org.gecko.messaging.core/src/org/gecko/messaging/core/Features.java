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

import static org.gecko.messaging.api.MessagingConstants.*;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.gecko.messaging.api.FeatureNotSupportedException;
import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.acknowledge.AcknowledgeMessageContext;
import org.gecko.messaging.api.cloudevents.CloudEventMessageContext;

/**
 * The feature set of an instance, and the check that a context uses no feature outside of it — the
 * rule "fail on unsupported intents" (RFP 0192, MSG100).
 */
public final class Features {

	private final Set<String> features;

	/**
	 * Creates the feature set.
	 *
	 * @param features the supported features
	 */
	public Features(Collection<String> features) {
		this.features = Collections.unmodifiableSet(new LinkedHashSet<>(features));
	}

	/**
	 * Returns the features.
	 *
	 * @return the unmodifiable set of features
	 */
	public Set<String> asSet() {
		return features;
	}

	/**
	 * Returns whether the feature is supported.
	 *
	 * @param feature the feature
	 * @return {@code true} if supported
	 */
	public boolean has(String feature) {
		return features.contains(feature);
	}

	/**
	 * Fails if the feature is not supported.
	 *
	 * @param feature the feature
	 * @param what what needs it, for the message
	 * @throws FeatureNotSupportedException if the feature is not supported
	 */
	public void require(String feature, String what) {
		if (!features.contains(feature)) {
			throw new FeatureNotSupportedException(feature, what + " needs the feature '" + feature + "', which this instance does not support");
		}
	}

	/**
	 * Checks a context used for publishing.
	 *
	 * @param context the context
	 * @throws FeatureNotSupportedException on the first unsupported feature
	 */
	public void checkPublish(MessageContext context) {
		checkCommon(context);
		if (context.getContentType() != null || context.getContentEncoding() != null) {
			require(FEATURE_CONTENT_TYPE, "A content type or encoding");
		}
		if (!context.getHeaders().isEmpty()) {
			require(FEATURE_HEADERS, "Message headers");
		}
		if (context.getCorrelationId() != null || context.getReplyToChannel() != null) {
			// a correlation id or reply-to travels with the message only on reply-to capable instances
			require(FEATURE_REPLY_TO, "A correlation id or reply-to channel");
		}
		if (context.getExtensions().get(EXTENSION_TIME_TO_LIVE) != null) {
			require(FEATURE_TIME_TO_LIVE, "A time-to-live");
		}
		if (Boolean.TRUE.equals(bool(context.getExtensions().get(EXTENSION_RETAIN)))) {
			require(FEATURE_RETAIN, "A retained message");
		}
		if (context instanceof CloudEventMessageContext) {
			require(FEATURE_CLOUD_EVENTS, "A CloudEvent");
		}
	}

	/**
	 * Checks a context used for subscribing.
	 *
	 * @param context the context
	 * @throws FeatureNotSupportedException on the first unsupported feature
	 */
	public void checkSubscribe(MessageContext context) {
		checkCommon(context);
		if (!has(FEATURE_WILDCARD) && hasWildcard(context.getChannel())) {
			// a protocol without wildcards would take the symbol literally and receive nothing
			throw new FeatureNotSupportedException(FEATURE_WILDCARD, "Wildcards in channel names");
		}
		if (context instanceof AcknowledgeMessageContext ack && (ack.getAcknowledgeFilter() != null
				|| ack.getAcknowledgeFunction() != null || ack.isManualAcknowledge() || ack.getPostAcknowledge() != null
				|| ack.getPostReject() != null || ack.isRequeueOnReject())) {
			require(FEATURE_ACKNOWLEDGE, "Acknowledgement");
		}
	}

	/** Segment of a channel name that is a wildcard in one of the protocols: {@code + # *}. */
	static boolean hasWildcard(String channel) {
		if (channel == null) {
			return false;
		}
		for (String segment : channel.split("[/.]", -1)) {
			if (segment.equals("+") || segment.equals("#") || segment.equals("*")) {
				return true;
			}
		}
		return false;
	}

	private void checkCommon(MessageContext context) {
		if (context.getChannelExtension() != null) {
			require(FEATURE_CHANNEL_EXTENSION, "A channel extension");
		}
		Map<String, Object> ext = context.getExtensions();
		Object qos = ext.get(EXTENSION_QOS);
		if (qos != null) {
			int q = qos(context);
			if (q != QOS_AT_MOST_ONCE) {
				require(FEATURE_QOS, "Quality of service " + q);
			}
		}
		if (Boolean.TRUE.equals(bool(ext.get(EXTENSION_DURABLE)))) {
			require(FEATURE_DURABLE, "A durable channel");
		}
		if (Boolean.TRUE.equals(bool(ext.get(EXTENSION_AUTO_CREATE)))) {
			require(FEATURE_AUTO_CREATE, "Creating channels");
		}
		Object type = ext.get(EXTENSION_CHANNEL_TYPE);
		if (CHANNEL_TYPE_QUEUE.equals(type)) {
			require(FEATURE_POINT_TO_POINT, "A queue");
		} else if (CHANNEL_TYPE_TOPIC.equals(type)) {
			require(FEATURE_PUBLISH_SUBSCRIBE, "A topic");
		} else if (type != null) {
			throw new FeatureNotSupportedException(EXTENSION_CHANNEL_TYPE, "Unknown channel type '" + type + "'");
		}
	}

	/**
	 * Returns the quality of service of a context.
	 *
	 * @param context the context
	 * @return the QoS, {@code -1} if not set
	 * @throws FeatureNotSupportedException if the value is no valid QoS
	 */
	public static int qos(MessageContext context) {
		Object qos = context.getExtensions().get(EXTENSION_QOS);
		if (qos == null) {
			return -1;
		}
		int q;
		try {
			q = qos instanceof Number n ? n.intValue() : Integer.parseInt(qos.toString().trim());
		} catch (NumberFormatException e) {
			throw new FeatureNotSupportedException(FEATURE_QOS, "Invalid quality of service '" + qos + "'");
		}
		if (q < QOS_AT_MOST_ONCE || q > QOS_EXACTLY_ONCE) {
			throw new FeatureNotSupportedException(FEATURE_QOS, "Invalid quality of service " + q);
		}
		return q;
	}

	/**
	 * Reads a boolean extension value.
	 *
	 * @param value the value
	 * @return the boolean, or {@code null}
	 */
	public static Boolean bool(Object value) {
		if (value == null) {
			return null;
		}
		return value instanceof Boolean b ? b : Boolean.valueOf(value.toString().trim());
	}

	/**
	 * Reads a long extension value.
	 *
	 * @param value the value
	 * @return the long, or {@code null}
	 */
	public static Long number(Object value) {
		if (value == null) {
			return null;
		}
		return value instanceof Number n ? n.longValue() : Long.valueOf(value.toString().trim());
	}

}
