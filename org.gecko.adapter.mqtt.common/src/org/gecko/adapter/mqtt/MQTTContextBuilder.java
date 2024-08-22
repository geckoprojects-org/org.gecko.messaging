/*
 * Copyright (c) 2012 - 2024 Data In Motion and others.
 * All rights reserved. 
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Data In Motion - initial API and implementation
 */

package org.gecko.adapter.mqtt;

import org.gecko.osgi.messaging.MessagingContext;
import org.gecko.osgi.messaging.SimpleMessagingContextBuilder;

/**
 * Builder for the MQTT MessageContext
 * 
 * @author Mark Hoffmann
 * @since 10.10.2017
 */
public class MQTTContextBuilder extends SimpleMessagingContextBuilder {

	private MQTTContext context = new MQTTContext();

	/**
	 * Sets the {@link QoS} parameter
	 * 
	 * @param qos the qualitiy of service parameter. <code>null</code> defaults to
	 *            'at least one'
	 * @return
	 */
	public MQTTContextBuilder withQoS(QoS qos) {
		context.setQoS(qos == null ? QoS.AT_LEAST_ONE : qos);
		return this;
	}

	/**
	 * Sets retained to true
	 * 
	 * @return the builder instance
	 */
	public MQTTContextBuilder retained() {
		context.setRetained(true);
		return this;
	}

	/**
	 * Sets retained to true
	 * 
	 * @return the builder instance
	 */
	public MQTTContextBuilder setRetained(boolean retained) {
		context.setRetained(retained);
		return this;
	}

	@Override
	public MessagingContext build() {
		return buildContext(context);
	}

}
