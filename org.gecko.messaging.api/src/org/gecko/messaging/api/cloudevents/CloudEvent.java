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

import java.time.OffsetDateTime;
import java.util.Map;

import org.osgi.annotation.versioning.ProviderType;

/**
 * The context attributes of a CloudEvent (CloudEvents 1.0). The data of the event is the payload
 * of the message, its {@code datacontenttype} the content type of the message context.
 */
@ProviderType
public interface CloudEvent {

	/**
	 * Returns {@code id}.
	 *
	 * @return the event id, never {@code null}
	 */
	String getId();

	/**
	 * Returns {@code source}, a URI reference.
	 *
	 * @return the event source, never {@code null}
	 */
	String getSource();

	/**
	 * Returns {@code specversion}.
	 *
	 * @return the spec version, {@value CloudEventConstants#SPEC_VERSION}
	 */
	String getSpecVersion();

	/**
	 * Returns {@code type}.
	 *
	 * @return the event type, never {@code null}
	 */
	String getType();

	/**
	 * Returns {@code dataschema}.
	 *
	 * @return the data schema URI, or {@code null}
	 */
	String getDataSchema();

	/**
	 * Returns {@code subject}.
	 *
	 * @return the subject, or {@code null}
	 */
	String getSubject();

	/**
	 * Returns {@code time}.
	 *
	 * @return the time of the occurrence, or {@code null}
	 */
	OffsetDateTime getTime();

	/**
	 * Returns the extension attributes. The message headers of a CloudEvent message are its
	 * extension attributes; this map holds the same entries.
	 *
	 * @return the extension attributes, never {@code null}
	 */
	Map<String, Object> getExtensions();

}
