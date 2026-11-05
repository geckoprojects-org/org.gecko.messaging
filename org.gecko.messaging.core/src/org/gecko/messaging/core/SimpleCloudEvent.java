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
import java.util.Map;

import org.gecko.messaging.api.cloudevents.CloudEvent;
import org.gecko.messaging.api.cloudevents.CloudEventConstants;

/**
 * Immutable CloudEvent attributes.
 *
 * @param getId the id
 * @param getSource the source
 * @param getType the type
 * @param getDataSchema the data schema, or {@code null}
 * @param getSubject the subject, or {@code null}
 * @param getTime the time, or {@code null}
 * @param getExtensions the extension attributes, unmodifiable
 */
public record SimpleCloudEvent(String getId, String getSource, String getType, String getDataSchema, String getSubject,
		OffsetDateTime getTime, Map<String, Object> getExtensions) implements CloudEvent {

	/**
	 * Makes the extensions unmodifiable.
	 *
	 * @param getId the id
	 * @param getSource the source
	 * @param getType the type
	 * @param getDataSchema the data schema
	 * @param getSubject the subject
	 * @param getTime the time
	 * @param getExtensions the extensions
	 */
	public SimpleCloudEvent {
		getExtensions = getExtensions == null ? Map.of() : Map.copyOf(getExtensions);
	}

	@Override
	public String getSpecVersion() {
		return CloudEventConstants.SPEC_VERSION;
	}

}
