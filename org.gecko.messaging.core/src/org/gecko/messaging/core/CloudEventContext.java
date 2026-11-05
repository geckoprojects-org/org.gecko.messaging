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

import org.gecko.messaging.api.acknowledge.AcknowledgeHandler;
import org.gecko.messaging.api.cloudevents.CloudEvent;
import org.gecko.messaging.api.cloudevents.CloudEventMessageContext;

/**
 * Message context of a CloudEvent message.
 */
public final class CloudEventContext extends SimpleMessageContext implements CloudEventMessageContext {

	private final CloudEvent cloudEvent;

	CloudEventContext(ContextData data, CloudEvent cloudEvent) {
		super(data);
		this.cloudEvent = cloudEvent;
	}

	private CloudEventContext(CloudEventContext source, AcknowledgeHandler handler) {
		super(source, handler);
		this.cloudEvent = source.cloudEvent;
	}

	@Override
	public SimpleMessageContext withAcknowledgeHandler(AcknowledgeHandler handler) {
		return new CloudEventContext(this, handler);
	}

	@Override
	public CloudEvent getCloudEvent() {
		return cloudEvent;
	}

	@Override
	public String toString() {
		return super.toString() + "[ce type=" + cloudEvent.getType() + ", source=" + cloudEvent.getSource() + ", id="
				+ cloudEvent.getId() + "]";
	}

}
