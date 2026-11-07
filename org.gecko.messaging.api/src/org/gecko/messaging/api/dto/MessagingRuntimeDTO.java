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
package org.gecko.messaging.api.dto;

import org.osgi.dto.DTO;
import org.osgi.framework.dto.ServiceReferenceDTO;

/**
 * The state of a messaging instance.
 *
 * @NotThreadSafe
 */
public class MessagingRuntimeDTO extends DTO {

	/** The runtime service of the instance. */
	public ServiceReferenceDTO serviceDTO;

	/** The instance name. */
	public String name;

	/** The provider of the implementation. */
	public String providerName;

	/** The protocols of the instance. */
	public String[] protocols;

	/** The features of the instance. */
	public String[] features;

	/** The connection URI, without credentials. */
	public String connectionURI;

	/** The connection state, a name of {@link org.gecko.messaging.api.runtime.ConnectionState}. */
	public String connectionState;

	/** The number of reconnects since the instance started. */
	public long reconnects;

	/**
	 * The messages of stream publishes that failed and were skipped
	 * ({@code streamPublishFailures=continue}).
	 */
	public long streamPublishFailures;

	/**
	 * The single publishes waiting for the connection ({@code publishAwaitConnection}).
	 */
	public int awaitingPublishes;

	/** The active subscriptions. */
	public SubscriptionDTO[] subscriptions;

	/** The bound reply-to handlers. */
	public ReplyToSubscriptionDTO[] replyToSubscriptions;

}
