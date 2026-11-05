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
package org.gecko.messaging.api.cloudevents;

/**
 * Constants of the CloudEvents support.
 */
public final class CloudEventConstants {

	private CloudEventConstants() {
		// constants only
	}

	/** The CloudEvents spec version supported. */
	public static final String SPEC_VERSION = "1.0";

	/** The media type of the structured content mode in JSON. */
	public static final String STRUCTURED_JSON_CONTENT_TYPE = "application/cloudevents+json";

	/** Binary content mode: attributes in protocol headers, data is the payload. */
	public static final String MODE_BINARY = "binary";

	/** Structured content mode: the whole event in a JSON envelope, the payload is the envelope. */
	public static final String MODE_STRUCTURED = "structured";

	/**
	 * Extension attribute carrying the correlation id on protocols without a native one.
	 */
	public static final String EXTENSION_CORRELATION_ID = "correlationid";

	/**
	 * Extension attribute carrying the reply-to channel on protocols without a native one.
	 */
	public static final String EXTENSION_REPLY_TO = "replyto";

	/**
	 * Extension attribute carrying the content encoding on protocols without a native one.
	 */
	public static final String EXTENSION_CONTENT_ENCODING = "contentencoding";

}
