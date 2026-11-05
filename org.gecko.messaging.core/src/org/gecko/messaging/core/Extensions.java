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

import org.gecko.messaging.api.FeatureNotSupportedException;
import org.gecko.messaging.api.MessageContext;

/**
 * Reads the values of context extensions, which arrive typed from code and as strings from
 * configurations.
 */
public final class Extensions {

	private Extensions() {
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
