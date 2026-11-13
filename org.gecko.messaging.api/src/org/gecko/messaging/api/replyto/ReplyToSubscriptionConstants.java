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
package org.gecko.messaging.api.replyto;

/**
 * Service properties of the reply-to whiteboard.
 */
public final class ReplyToSubscriptionConstants {

	private ReplyToSubscriptionConstants() {
		// constants only
	}

	/** Service property of a handler: the channel requests arrive on. Type {@code String}, required. */
	public static final String REPLY_TO_SUBSCRIPTION_CHANNEL_PROPERTY = "osgi.messaging.replyToSubscription.channel";

	/**
	 * Service property of a handler: the channel extension of the request channel, e.g. a routing
	 * key. Type {@code String}, optional.
	 */
	public static final String REPLY_TO_SUBSCRIPTION_CHANNEL_EXTENSION_PROPERTY = "osgi.messaging.replyToSubscription.channelExtension";

	/**
	 * Service property of a handler: the channel responses go to when a request names no reply-to
	 * channel. Type {@code String}, optional; without it such requests get no response.
	 */
	public static final String REPLY_TO_SUBSCRIPTION_REPLY_CHANNEL_PROPERTY = "osgi.messaging.replyToSubscription.replyChannel";

	/**
	 * Service property of a {@link ReplyToSessionHandler}: the prefix of the channel each of its
	 * sessions receives the requester's messages on, followed by {@code /} and a generated id.
	 * Type {@code String}, optional; without it the instance generates the channel as for its
	 * reply channels. With it the responder side of a session stays in a tree a broker ACL can
	 * grant, as the requester's side does with a named reply-to channel.
	 */
	public static final String REPLY_TO_SUBSCRIPTION_SESSION_CHANNEL_PREFIX_PROPERTY = "osgi.messaging.replyToSubscription.sessionChannelPrefix";

	/**
	 * Service property of a handler: the type of the request channel,
	 * {@link org.gecko.messaging.api.MessagingConstants#CHANNEL_TYPE_QUEUE} for competing handlers
	 * or {@link org.gecko.messaging.api.MessagingConstants#CHANNEL_TYPE_TOPIC}. Type {@code String},
	 * optional; without it the instance default applies.
	 */
	public static final String REPLY_TO_SUBSCRIPTION_CHANNEL_TYPE_PROPERTY = "osgi.messaging.replyToSubscription.channelType";

}
