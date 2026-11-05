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

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.gecko.messaging.api.Message;

/**
 * The mutable values a {@link SimpleMessageContextBuilder} collects. Package private; contexts copy
 * it on creation.
 */
final class ContextData {

	String channel;
	String channelExtension;
	String contentType;
	String contentEncoding;
	String correlationId;
	String replyToChannel;
	final Map<String, Object> headers = new LinkedHashMap<>();
	final Map<String, Object> extensions = new LinkedHashMap<>();

	// acknowledgement configuration of a subscription
	Predicate<Message> acknowledgeFilter;
	Consumer<Message> acknowledgeFunction;
	boolean manualAcknowledge;
	boolean requeueOnReject;
	Consumer<Message> postAcknowledge;
	Consumer<Message> postReject;
	boolean acknowledgeConfigured;

	// CloudEvent attributes
	boolean cloudEvent;
	String ceId;
	String ceSource;
	String ceType;
	String ceSubject;
	String ceDataSchema;
	OffsetDateTime ceTime;

}
