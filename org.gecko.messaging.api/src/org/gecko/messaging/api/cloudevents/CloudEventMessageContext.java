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

import org.gecko.messaging.api.MessageContext;
import org.osgi.annotation.versioning.ProviderType;

/**
 * The context of a message that is a CloudEvent. Instances with
 * {@link org.gecko.messaging.api.MessagingConstants#FEATURE_CLOUD_EVENTS} deliver received
 * CloudEvents with such a context; messages that are not CloudEvents keep a plain context.
 */
@ProviderType
public interface CloudEventMessageContext extends MessageContext {

	/**
	 * Returns the CloudEvent attributes.
	 *
	 * @return the CloudEvent, never {@code null}
	 */
	CloudEvent getCloudEvent();

}
