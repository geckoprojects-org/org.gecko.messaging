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

import org.gecko.messaging.api.MessageContextBuilder;
import org.osgi.annotation.versioning.ProviderType;

/**
 * Makes the message being built a CloudEvent. Obtained with
 * {@code MessageContextBuilder.adapt(CloudEventMessageContextBuilder.class)} from instances that
 * support {@link org.gecko.messaging.api.MessagingConstants#FEATURE_CLOUD_EVENTS}. The built
 * context is a {@link CloudEventMessageContext}.
 * <p>
 * {@code type} and {@code source} are required. Without {@code id} a unique one is generated,
 * without {@code time} the time of building is taken. The content type of the message builder is
 * the {@code datacontenttype}; its headers are the extension attributes.
 */
@ProviderType
public interface CloudEventMessageContextBuilder {

	/**
	 * Sets {@code id}.
	 *
	 * @param id the event id
	 * @return this builder
	 */
	CloudEventMessageContextBuilder id(String id);

	/**
	 * Sets {@code source}.
	 *
	 * @param source the source, a URI reference
	 * @return this builder
	 */
	CloudEventMessageContextBuilder source(String source);

	/**
	 * Sets {@code type}.
	 *
	 * @param type the event type
	 * @return this builder
	 */
	CloudEventMessageContextBuilder type(String type);

	/**
	 * Sets {@code subject}.
	 *
	 * @param subject the subject
	 * @return this builder
	 */
	CloudEventMessageContextBuilder subject(String subject);

	/**
	 * Sets {@code dataschema}.
	 *
	 * @param dataSchema the data schema URI
	 * @return this builder
	 */
	CloudEventMessageContextBuilder dataSchema(String dataSchema);

	/**
	 * Sets {@code time}.
	 *
	 * @param time the time of the occurrence
	 * @return this builder
	 */
	CloudEventMessageContextBuilder time(OffsetDateTime time);

	/**
	 * Takes the attributes of an existing CloudEvent, for example to forward it.
	 *
	 * @param cloudEvent the CloudEvent
	 * @return this builder
	 */
	CloudEventMessageContextBuilder withCloudEvent(CloudEvent cloudEvent);

	/**
	 * Returns the builder this one was adapted from.
	 *
	 * @return the message context builder
	 */
	MessageContextBuilder messageContextBuilder();

}
