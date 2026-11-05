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
package org.gecko.messaging.api;

import org.osgi.annotation.versioning.ProviderType;
import org.osgi.util.pushstream.PushStream;

/**
 * Subscribes to channels.
 * <p>
 * The subscription is made when a terminal operation connects to the returned stream, and ends
 * when the stream is closed. If the instance is connected, the subscription is in place on the
 * broker when the terminal operation returns; messages published afterwards are received. A subscription survives connection losses: the implementation
 * restores it after a reconnect without the consumer doing anything. Connection changes are
 * reported to {@link org.gecko.messaging.api.runtime.ConnectionListener}s, not through the
 * stream — a gap in the stream is visible there. The stream fails only when the subscription
 * cannot be restored (for example the broker denied it) and is closed when the instance goes away.
 */
@ProviderType
public interface MessageSubscription {

	/**
	 * Subscribes to the given channel with the defaults of the instance.
	 *
	 * @param channel the channel, a topic or queue name; may contain wildcards if the instance
	 *            supports {@link MessagingConstants#FEATURE_WILDCARD}
	 * @return the stream of received messages
	 */
	PushStream<Message> subscribe(String channel);

	/**
	 * Subscribes with the given context, which carries the channel and the subscription options,
	 * like the quality of service or the acknowledgement handling.
	 *
	 * @param context the subscription context; {@code null} uses the context the instance is
	 *            configured with
	 * @return the stream of received messages
	 * @throws MessagingException if neither the context nor the configuration name a channel
	 */
	PushStream<Message> subscribe(MessageContext context);

}
