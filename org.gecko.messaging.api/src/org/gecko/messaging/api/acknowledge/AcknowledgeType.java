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
package org.gecko.messaging.api.acknowledge;

/**
 * The acknowledgement state of a received message.
 */
public enum AcknowledgeType {

	/** Received and neither acknowledged nor rejected yet. */
	RECEIVED,

	/** Acknowledged: the broker may forget the message. */
	ACKNOWLEDGED,

	/** Rejected: the broker re-queues, dead-letters or discards the message. */
	REJECTED,

	/** The protocol or the subscription has no acknowledgement; the message counts as delivered. */
	UNSUPPORTED

}
