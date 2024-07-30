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

package org.gecko.osgi.messaging;

import java.util.Map;

public enum ReplyToPolicy {
		
		SINGLE,
		MULTIPLE;

		public static ReplyToPolicy getPolicy(Map<String, Object> properties) {
			if (properties == null) {
				return SINGLE;
			}
			Object ov = properties.get(MessagingContext.PROP_REPLY_TO_POLICY);
			String v = ov.toString();
			try {
				return valueOf(v);
			} catch (Exception e) {
				return SINGLE;
			}
		}
	}