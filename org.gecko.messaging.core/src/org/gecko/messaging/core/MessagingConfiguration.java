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

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.MessagingConstants;
import org.gecko.messaging.api.MessagingException;
import org.gecko.messaging.api.cloudevents.CloudEventConstants;

/**
 * The configuration every messaging instance shares, read from the component properties. Protocol
 * specific values are read by the protocol from {@link #raw()}.
 * <p>
 * Keys:
 * <ul>
 * <li>{@code osgi.messaging.name} — instance name, required</li>
 * <li>{@code uri} — connection URI</li>
 * <li>{@code username}, {@code .password} (or {@code password})</li>
 * <li>{@code tls.keystore}, {@code tls.truststore} with their passwords and types — client
 * certificate and trusted certificates of a TLS connection, see {@link TlsSettings}</li>
 * <li>{@code clientId} — stable client identity; default the instance name</li>
 * <li>{@code connectTimeout} — ms per connect attempt, default 10000</li>
 * <li>{@code reconnectDelay}, {@code reconnectMaxDelay} — ms, backoff 1000 doubling to 30000</li>
 * <li>{@code replyTimeout} — ms a reply-to publisher waits, default 30000</li>
 * <li>{@code replyChannelPrefix} — prefix of generated reply channels</li>
 * <li>{@code replyToAllowed} — prefixes a reply-to channel of a received request must start with
 * to be answered (a String array, or one String with comma-separated prefixes; a prefix is
 * matched as given, end it with the separator to match a channel subtree), {@code *} for any
 * channel; default the start of generated reply channels
 * ({@code osgi/messaging/reply/}) and the configured {@code replyChannelPrefix}. A request names
 * its reply channel itself: without the check a sender could direct responses to any channel
 * the instance may write to.</li>
 * <li>{@code cloudEvents} — {@code none} (default), {@code binary} or {@code structured}</li>
 * <li>{@code cloudEvents.acceptPlain} — with CloudEvents, also deliver received messages that are
 * no CloudEvent, unchanged as plain messages; default {@code false}: an instance with
 * CloudEvents takes CloudEvents only and drops (and counts) the rest</li>
 * <li>{@code workerThreads} — threads delivering messages and running handlers, default 4;
 * {@code virtual} for a virtual thread per task</li>
 * <li>{@code bufferSize} — buffer per subscription, default 1024 messages; the receive window
 * of a binding ({@code receiveMaximum}, {@code prefetch}) is lowered to it</li>
 * <li>{@code bufferBytes} — payload bytes per subscription buffer, default 16 MiB</li>
 * <li>{@code queuePolicy} — when a buffer is full: {@code BLOCK}, {@code DISCARD_OLDEST},
 * {@code FAIL}; default {@code BLOCK}, which holds back the protocol thread (back-pressure)</li>
 * <li>{@code context.channel}, {@code context.channelExtension}, {@code context.contentType},
 * {@code context.contentEncoding}, {@code context.extension.<key>} — the context used for
 * {@code subscribe(null)} and {@code publish(message, (MessageContext) null)}</li>
 * </ul>
 */
public final class MessagingConfiguration {

	/** No CloudEvents. */
	public static final String CLOUD_EVENTS_NONE = "none";

	private final Map<String, Object> raw;
	private final String name;
	private final List<String> replyToAllowed;

	/**
	 * Reads the configuration.
	 *
	 * @param properties the component properties
	 * @throws MessagingException if the instance name is missing
	 */
	public MessagingConfiguration(Map<String, ?> properties) {
		this.raw = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
		Object n = raw.get(MessagingConstants.MESSAGING_NAME_PROPERTY);
		if (n == null || n.toString().isBlank()) {
			throw new MessagingException("The configuration has no '" + MessagingConstants.MESSAGING_NAME_PROPERTY + "'");
		}
		this.name = n.toString();
		String mode = cloudEventsMode();
		if (!CLOUD_EVENTS_NONE.equals(mode) && !CloudEventConstants.MODE_BINARY.equals(mode)
				&& !CloudEventConstants.MODE_STRUCTURED.equals(mode)) {
			throw new MessagingException("Unknown cloudEvents mode '" + mode + "'");
		}
		this.replyToAllowed = readReplyToAllowed();
	}

	/**
	 * Returns all properties.
	 *
	 * @return the unmodifiable properties
	 */
	public Map<String, Object> raw() {
		return raw;
	}

	/**
	 * Returns the instance name.
	 *
	 * @return the name
	 */
	public String name() {
		return name;
	}

	/**
	 * Returns a string value.
	 *
	 * @param key the key
	 * @param defaultValue the default
	 * @return the value or the default
	 */
	public String string(String key, String defaultValue) {
		Object v = raw.get(key);
		if (v instanceof String[] a) {
			v = a.length == 0 ? null : a[0];
		}
		return v == null || v.toString().isEmpty() ? defaultValue : v.toString();
	}

	/**
	 * Returns a long value.
	 *
	 * @param key the key
	 * @param defaultValue the default
	 * @return the value or the default
	 */
	public long number(String key, long defaultValue) {
		String v = string(key, null);
		try {
			return v == null ? defaultValue : Long.parseLong(v.trim());
		} catch (NumberFormatException e) {
			throw new MessagingException("'" + key + "' is no number: " + v);
		}
	}

	/**
	 * Returns a boolean value.
	 *
	 * @param key the key
	 * @param defaultValue the default
	 * @return the value or the default
	 */
	public boolean bool(String key, boolean defaultValue) {
		String v = string(key, null);
		return v == null ? defaultValue : Boolean.parseBoolean(v.trim());
	}

	/** @return the connection URI, or {@code null} */
	public String uri() {
		return string("uri", null);
	}

	/** @return the user name, or {@code null} */
	public String username() {
		return string("username", null);
	}

	/** @return the password, or {@code null} */
	public String password() {
		return string(".password", string("password", null));
	}

	/** @return the client id */
	public String clientId() {
		return string("clientId", name);
	}

	/** @return the timeout of a connect attempt in ms */
	public long connectTimeout() {
		return number("connectTimeout", 10_000);
	}

	/** @return the first reconnect delay in ms */
	public long reconnectDelay() {
		return number("reconnectDelay", 1_000);
	}

	/** @return the largest reconnect delay in ms */
	public long reconnectMaxDelay() {
		return number("reconnectMaxDelay", 30_000);
	}

	/** @return the default reply timeout in ms */
	public long replyTimeout() {
		return number("replyTimeout", 30_000);
	}

	/**
	 * Returns the prefix of generated reply channels.
	 *
	 * @param defaultPrefix the protocol default
	 * @return the prefix
	 */
	public String replyChannelPrefix(String defaultPrefix) {
		return string("replyChannelPrefix", defaultPrefix);
	}

	/**
	 * Returns the prefixes a reply-to channel of a received request must start with.
	 *
	 * @return the prefixes, {@code *} for any channel
	 */
	public List<String> replyToAllowed() {
		return replyToAllowed;
	}

	private List<String> readReplyToAllowed() {
		Object v = raw.get("replyToAllowed");
		if (v instanceof String s) {
			// one String from a configuration file may list several prefixes
			return Arrays.stream(s.split(",")).map(String::trim).filter(p -> !p.isEmpty()).toList();
		}
		if (v instanceof String[] a) {
			return List.of(a);
		}
		if (v instanceof Collection<?> c) {
			return c.stream().map(String::valueOf).toList();
		}
		// secure by default: generated reply channels only
		// a generated channel is the prefix, '/', the rest: allowing the prefix plus '/' keeps
		// 'app/replies' from also allowing 'app/replies-admin'
		String own = string("replyChannelPrefix", null);
		return own == null ? List.of(MessagingConstants.DEFAULT_REPLY_CHANNEL_PREFIX)
				: List.of(MessagingConstants.DEFAULT_REPLY_CHANNEL_PREFIX, own.endsWith("/") ? own : own + "/");
	}

	/**
	 * Returns whether a reply-to channel of a received request may be answered.
	 *
	 * @param channel the reply-to channel the request names
	 * @return {@code true} if the channel starts with an allowed prefix, or any is allowed
	 */
	public boolean replyToAllowed(String channel) {
		for (String prefix : replyToAllowed) {
			if (ANY.equals(prefix) || channel.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	/** {@code replyToAllowed} value for any channel. */
	static final String ANY = "*";

	/** @return {@code none}, {@code binary} or {@code structured} */
	public String cloudEventsMode() {
		return string("cloudEvents", CLOUD_EVENTS_NONE).toLowerCase();
	}

	/** @return whether an instance with CloudEvents also delivers plain messages */
	public boolean cloudEventsAcceptPlain() {
		return bool("cloudEvents.acceptPlain", false);
	}

	/** @return whether CloudEvents are on */
	public boolean cloudEvents() {
		return !CLOUD_EVENTS_NONE.equals(cloudEventsMode());
	}

	/** @return the number of delivery threads */
	public int workerThreads() {
		return virtualWorkers() ? 4 : (int) Math.max(1, number("workerThreads", 4));
	}

	/** @return whether delivery runs on virtual threads, {@code workerThreads=virtual} */
	public boolean virtualWorkers() {
		return "virtual".equalsIgnoreCase(string("workerThreads", "").trim());
	}

	/** @return the buffer size per subscription */
	public int bufferSize() {
		return (int) Math.max(1, number("bufferSize", 1024));
	}

	/** @return the payload bytes the buffer of a subscription holds at most, default 16 MiB */
	public long bufferBytes() {
		return Math.max(1, number("bufferBytes", 16L << 20));
	}

	/**
	 * Returns the queue policy.
	 *
	 * @param defaultPolicy the protocol default
	 * @return {@code BLOCK}, {@code DISCARD_OLDEST} or {@code FAIL}
	 */
	public String queuePolicy(String defaultPolicy) {
		return string("queuePolicy", defaultPolicy).toUpperCase();
	}

	/**
	 * Returns the configured default context.
	 *
	 * @return the default context, or {@code null} if none is configured
	 */
	public MessageContext defaultContext() {
		SimpleMessageContextBuilder b = new SimpleMessageContextBuilder();
		boolean any = false;
		String channel = string("context.channel", null);
		if (channel != null) {
			b.channel(channel, string("context.channelExtension", null));
			any = true;
		}
		String ct = string("context.contentType", null);
		if (ct != null) {
			b.contentType(ct);
			any = true;
		}
		String ce = string("context.contentEncoding", null);
		if (ce != null) {
			b.contentEncoding(ce);
			any = true;
		}
		String prefix = "context.extension.";
		for (Map.Entry<String, Object> e : raw.entrySet()) {
			if (e.getKey().startsWith(prefix)) {
				b.extensionEntry(e.getKey().substring(prefix.length()), e.getValue());
				any = true;
			}
		}
		return any ? b.buildContext() : null;
	}

	/**
	 * Returns the URI without user info, for display.
	 *
	 * @return the URI without credentials
	 */
	public String displayUri() {
		String u = uri();
		if (u == null) {
			return null;
		}
		int scheme = u.indexOf("://");
		if (scheme <= 0) {
			return u;
		}
		// the user info ends at the last '@' of the authority: a password may contain '@', a path too
		int start = scheme + 3;
		int end = u.length();
		for (int i = start; i < u.length(); i++) {
			char c = u.charAt(i);
			if (c == '/' || c == '?' || c == '#') {
				end = i;
				break;
			}
		}
		int at = u.lastIndexOf('@', end - 1);
		if (at >= start) {
			return u.substring(0, start) + u.substring(at + 1);
		}
		return u;
	}

}
