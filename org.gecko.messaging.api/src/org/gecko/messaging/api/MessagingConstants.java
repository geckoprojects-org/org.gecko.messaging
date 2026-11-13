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

/**
 * Constants of the messaging specification: service properties, feature names and the standard
 * keys of {@link MessageContext#getExtensions() context extensions}.
 */
public final class MessagingConstants {

	private MessagingConstants() {
		// constants only
	}

	/** Name of the implementation capability in the {@code osgi.implementation} namespace. */
	public static final String MESSAGING_IMPLEMENTATION = "osgi.messaging";

	/** Version of this specification. */
	public static final String MESSAGING_SPECIFICATION_VERSION = "1.0.0";

	/*
	 * Service properties every service of a messaging instance carries.
	 */

	/**
	 * Service property: the name of the messaging instance, unique within the framework. Type
	 * {@code String}.
	 */
	public static final String MESSAGING_NAME_PROPERTY = "osgi.messaging.name";

	/** Service property: the provider of the implementation, e.g. {@code gecko}. Type {@code String}. */
	public static final String MESSAGING_PROVIDER_PROPERTY = "osgi.messaging.provider";

	/**
	 * Service property: the protocols the instance speaks, e.g. {@code mqtt}, {@code amqp},
	 * {@code websocket}. Type {@code String+}.
	 */
	public static final String MESSAGING_PROTOCOL_PROPERTY = "osgi.messaging.protocol";

	/**
	 * Service property: the features the instance supports, see the {@code FEATURE_*} constants.
	 * Type {@code String+}. Using a feature the instance does not announce fails with a
	 * {@link FeatureNotSupportedException}.
	 */
	public static final String MESSAGING_FEATURE_PROPERTY = "osgi.messaging.feature";

	/**
	 * Service property: the largest payload in bytes the instance can send, if it is limited. Type
	 * {@code Long}. Publishing a larger payload fails.
	 */
	public static final String MESSAGING_MAX_MESSAGE_SIZE_PROPERTY = "osgi.messaging.maxMessageSize";

	/**
	 * Service property of whiteboard services (reply-to handlers, connection listeners): a filter
	 * that selects the messaging instances, matched against the service properties of their
	 * {@link org.gecko.messaging.api.runtime.MessageServiceRuntime}. Type {@code String}.
	 */
	public static final String MESSAGING_TARGET_PROPERTY = "osgi.messaging.target";

	/*
	 * Features.
	 */

	/** Feature: publish-subscribe channels (topics). */
	public static final String FEATURE_PUBLISH_SUBSCRIBE = "publishSubscribe";

	/** Feature: point-to-point channels (queues) — one receiver per message. */
	public static final String FEATURE_POINT_TO_POINT = "pointToPoint";

	/** Feature: wildcards in channel names of subscriptions. */
	public static final String FEATURE_WILDCARD = "wildcard";

	/** Feature: channel extensions such as routing keys. */
	public static final String FEATURE_CHANNEL_EXTENSION = "channelExtension";

	/** Feature: content type and content encoding travel with the message. */
	public static final String FEATURE_CONTENT_TYPE = "contentType";

	/** Feature: application headers travel with the message. */
	public static final String FEATURE_HEADERS = "headers";

	/** Feature: quality of service, see {@link #EXTENSION_QOS}. */
	public static final String FEATURE_QOS = "qos";

	/** Feature: message time-to-live, see {@link #EXTENSION_TIME_TO_LIVE}. */
	public static final String FEATURE_TIME_TO_LIVE = "timeToLive";

	/** Feature: retained messages, see {@link #EXTENSION_RETAIN}. */
	public static final String FEATURE_RETAIN = "retain";

	/** Feature: channels are created on demand, see {@link #EXTENSION_AUTO_CREATE}. */
	public static final String FEATURE_AUTO_CREATE = "autoCreate";

	/** Feature: durable subscriptions / queues that survive the client, see {@link #EXTENSION_DURABLE}. */
	public static final String FEATURE_DURABLE = "durable";

	/** Feature: manual acknowledgement and rejection of received messages. */
	public static final String FEATURE_ACKNOWLEDGE = "acknowledge";

	/** Feature: reply-to publishing with a single response. */
	public static final String FEATURE_REPLY_TO = "replyTo";

	/** Feature: reply-to publishing with a stream of responses. */
	public static final String FEATURE_REPLY_TO_MANY = "replyToMany";

	/** Feature: the reply-to whiteboard for request handlers. */
	public static final String FEATURE_REPLY_TO_SUBSCRIPTION = "replyToSubscription";

	/**
	 * Feature: duplex sessions, {@code ReplyToPublisher.openSession} and the
	 * {@code ReplyToSessionHandler} whiteboard.
	 */
	public static final String FEATURE_REPLY_TO_SESSION = "replyToSession";

	/** Feature: a missing correlation id of a reply-to request is generated. */
	public static final String FEATURE_GENERATE_CORRELATION_ID = "generateCorrelationId";

	/** Feature: a missing reply-to channel of a reply-to request is generated. */
	public static final String FEATURE_GENERATE_REPLY_CHANNEL = "generateReplyChannel";

	/** Feature: messages are CloudEvents, see {@link org.gecko.messaging.api.cloudevents}. */
	public static final String FEATURE_CLOUD_EVENTS = "cloudEvents";

	/*
	 * Standard extension keys.
	 */

	/**
	 * Extension: the quality of service. Type {@code Integer}: {@link #QOS_AT_MOST_ONCE},
	 * {@link #QOS_AT_LEAST_ONCE} or {@link #QOS_EXACTLY_ONCE}.
	 */
	public static final String EXTENSION_QOS = "osgi.messaging.qos";

	/** At most once: send and forget. */
	public static final int QOS_AT_MOST_ONCE = 0;

	/** At least once: send and forward, duplicates possible. */
	public static final int QOS_AT_LEAST_ONCE = 1;

	/** Exactly once. */
	public static final int QOS_EXACTLY_ONCE = 2;

	/** Extension: time-to-live of a message in milliseconds. Type {@code Long}. */
	public static final String EXTENSION_TIME_TO_LIVE = "osgi.messaging.timeToLive";

	/** Extension: the broker keeps the message for later subscribers. Type {@code Boolean}. */
	public static final String EXTENSION_RETAIN = "osgi.messaging.retain";

	/** Extension: create the channel if it does not exist. Type {@code Boolean}. */
	public static final String EXTENSION_AUTO_CREATE = "osgi.messaging.autoCreate";

	/** Extension: the channel or subscription survives the client. Type {@code Boolean}. */
	public static final String EXTENSION_DURABLE = "osgi.messaging.durable";

	/**
	 * Extension: the type of the channel, {@link #CHANNEL_TYPE_TOPIC} or
	 * {@link #CHANNEL_TYPE_QUEUE}. Type {@code String}. Without it the instance default applies.
	 */
	public static final String EXTENSION_CHANNEL_TYPE = "osgi.messaging.channelType";

	/** A publish-subscribe channel. */
	public static final String CHANNEL_TYPE_TOPIC = "topic";

	/** A point-to-point channel. */
	public static final String CHANNEL_TYPE_QUEUE = "queue";

	/**
	 * Extension: how long a reply-to publisher waits for a response, in milliseconds. Type
	 * {@code Long}. Without it the instance default applies.
	 */
	public static final String EXTENSION_REPLY_TIMEOUT = "osgi.messaging.replyTo.timeout";

	/**
	 * Extension of a session request: the lease the requester asks for, in milliseconds — the
	 * time either side may stay silent before the session ends. Type {@code Long}. The responder
	 * keeps it within its {@code minSessionLease} and {@code maxSessionLease}; without it the
	 * instance's {@code sessionLease} applies.
	 */
	public static final String EXTENSION_SESSION_LEASE = "osgi.messaging.replyTo.sessionLease";

	/**
	 * Extension of a subscription: the messages its buffer holds at most. Type {@code Integer}, at
	 * most the instance's {@code bufferSize}, which applies without it.
	 */
	public static final String EXTENSION_BUFFER_SIZE = "osgi.messaging.bufferSize";

	/**
	 * Extension of a subscription: the payload bytes its buffer holds at most; a single larger
	 * message is taken into an empty buffer. Type {@code Long}, at most the instance's
	 * {@code bufferBytes}, which applies without it.
	 */
	public static final String EXTENSION_BUFFER_BYTES = "osgi.messaging.bufferBytes";

	/**
	 * Extension of a subscription: what happens when its buffer is full — {@code BLOCK} (wait),
	 * {@code DISCARD_OLDEST} (latest wins, discarded messages are counted) or {@code FAIL} (the
	 * subscription ends with an error). Type {@code String}. Without it the instance's
	 * {@code queuePolicy} applies.
	 */
	public static final String EXTENSION_QUEUE_POLICY = "osgi.messaging.queuePolicy";

	/**
	 * Extension of a subscription: the header that marks the first message of a group, such as a
	 * key frame of a video stream. {@code DISCARD_OLDEST} then discards whole groups — the oldest
	 * message and every following one up to the next group start — so that what stays can be
	 * used. Type {@code String}, the header name; a message starts a group if the header is
	 * {@code true}.
	 */
	public static final String EXTENSION_GROUP_HEADER = "osgi.messaging.groupHeader";

	/*
	 * Standard headers.
	 */

	/**
	 * Header of the last response of a stream of responses, value {@code true}. It ends the
	 * stream of {@link org.gecko.messaging.api.replyto.ReplyToPublisher#publishWithReplyMany(Message)}.
	 * The name is a valid CloudEvents extension name.
	 */
	public static final String HEADER_REPLY_COMPLETE = "replycomplete";

	/**
	 * Header of a response that ends a reply-to exchange with a failure, value a short reason. A
	 * single response with it fails the promise of
	 * {@link org.gecko.messaging.api.replyto.ReplyToPublisher#publishWithReply(Message)}; with
	 * {@link #HEADER_REPLY_COMPLETE} it ends a stream of responses with an error. The reason is
	 * meant for the requester: it carries no exception text or other detail of the responder.
	 * The name is a valid CloudEvents extension name.
	 */
	public static final String HEADER_REPLY_ERROR = "replyerror";

	/**
	 * Header of the control messages of a duplex session: {@code open}, {@code accept},
	 * {@code ready}, {@code ping} or {@code close}. Messages with it are not delivered to the
	 * application.
	 */
	public static final String HEADER_SESSION = "replysession";

	/** Header of an {@code accept}: the channel the requester sends the session's messages to. */
	public static final String HEADER_SESSION_CHANNEL = "replysessionchannel";

	/**
	 * Header of the last message of a duplex session that its responder ended with a reason of the
	 * application ({@link org.gecko.messaging.api.replyto.SessionEndedException}): the reason. The
	 * requester delivers the message and then fails the session with that reason. The name is a
	 * valid CloudEvents extension name.
	 */
	public static final String HEADER_SESSION_REASON = "replysessionreason";

	/** Header of an {@code open} and an {@code accept}: the lease in milliseconds. */
	public static final String HEADER_SESSION_LEASE = "replysessionlease";

	/**
	 * The start of generated reply channels, followed by the client id and a random part. A
	 * request naming another reply channel is not answered unless the answering instance allows
	 * it in its configuration: otherwise a sender could direct responses to any channel the
	 * instance may write to.
	 */
	public static final String DEFAULT_REPLY_CHANNEL_PREFIX = "osgi/messaging/reply/";

}
