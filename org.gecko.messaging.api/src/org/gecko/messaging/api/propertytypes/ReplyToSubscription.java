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
package org.gecko.messaging.api.propertytypes;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.CLASS;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.osgi.service.component.annotations.ComponentPropertyType;

/**
 * Component property type of reply-to handlers, see
 * {@link org.gecko.messaging.api.replyto.ReplyToSubscriptionConstants}. Combine it with
 * {@link MessagingTarget}.
 */
@ComponentPropertyType
@Documented
@Retention(CLASS)
@Target(TYPE)
public @interface ReplyToSubscription {

	/** Prefix of the property names. */
	String PREFIX_ = "osgi.messaging.replyToSubscription.";

	/**
	 * The channel requests arrive on.
	 *
	 * @return the request channel
	 */
	String channel();

	/**
	 * The channel extension of the request channel, e.g. a routing key.
	 *
	 * @return the channel extension, empty for none
	 */
	String channelExtension() default "";

	/**
	 * The channel for responses to requests without reply-to channel.
	 *
	 * @return the reply channel, empty for none
	 */
	String replyChannel() default "";

	/**
	 * The type of the request channel, {@code queue} or {@code topic}.
	 *
	 * @return the channel type, empty for the instance default
	 */
	String channelType() default "";

	/**
	 * The prefix of the channels a session handler takes for the messages of its sessions: the
	 * channel of a session is this prefix, {@code /} and a generated id. The requester must allow
	 * it (its {@code sessionChannelAllowed} or the extension
	 * {@code osgi.messaging.replyTo.sessionChannelAllowed}).
	 *
	 * @return the session channel prefix, empty for a generated channel
	 */
	String sessionChannelPrefix() default "";

}
