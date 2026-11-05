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
package org.gecko.messaging.websocket;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.gecko.messaging.api.MessagingConstants.*;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.gecko.messaging.api.MessagingException;
import org.gecko.messaging.core.AbstractMessagingInstance;
import org.gecko.messaging.core.MessagingConfiguration;
import org.gecko.messaging.core.SimpleMessageContext;
import org.gecko.messaging.core.SimpleMessageContextBuilder;
import org.gecko.messaging.core.SubscriptionHandle;
import org.osgi.util.promise.Deferred;
import org.osgi.util.promise.Promise;

/**
 * A messaging instance over raw WebSocket with the Jetty 12 client.
 * <p>
 * A channel is a URL path below the base URI: channel {@code a/b} of base {@code ws://host/m} is
 * the WebSocket {@code ws://host/m/a/b}. A hub relays every message of a path to all sessions of
 * that path. The instance keeps one session per channel in use, for publishing and receiving, and
 * one session on the base URI that tells whether the hub is reachable; when any of them closes
 * unexpectedly, the instance reconnects and restores its subscriptions. Every session is pinged
 * every {@code keepAliveInterval}, so idle sessions stay open and a dead link is noticed.
 * <p>
 * A WebSocket message is only bytes: the instance announces publish and subscribe. With structured
 * CloudEvents ({@code cloudEvents=structured}) the JSON envelope carries content type, headers,
 * correlation and reply-to, and the instance announces them; the binary mode needs headers and is
 * refused. Messages are sent as binary frames; received text frames are taken as UTF-8 bytes.
 * <p>
 * Configuration, besides the common keys: {@code uri} ({@code ws://} or {@code wss://}, the base
 * of the channel paths), {@code keepAliveInterval} (s, default 30; idle timeout three times that),
 * {@code maxThreads} (of the client, default 8), {@code maxMessageSize} (bytes, default 1 MiB),
 * {@code maxPublishSessions} (sessions kept open for channels only published to, default 16; the
 * least recently used one is closed beyond that — a request names its reply channel, so without
 * a bound a sender could make the instance open a session per request).
 * <p>
 * A channel is a relative path: an empty, {@code .} or {@code ..} segment is refused, so no
 * channel leaves the base path.
 */
public final class WebSocketInstance extends AbstractMessagingInstance {

	private static final Logger LOG = Logger.getLogger(WebSocketInstance.class.getName());

	/** The protocol name. */
	public static final String PROTOCOL = "websocket";

	static final List<String> FEATURES = List.of(FEATURE_PUBLISH_SUBSCRIBE, FEATURE_AUTO_CREATE);

	private final URI base;
	private final long keepAliveMillis;
	private WebSocketClient client;
	/** The session on the base URI: the hub is reachable while it is open. */
	private volatile ChannelSession control;
	/** Sessions by channel. */
	private final Map<String, ChannelSession> sessions = new ConcurrentHashMap<>();
	/** Local subscriptions by channel. */
	private final Map<String, Set<SubscriptionHandle>> subscriptions = new ConcurrentHashMap<>();
	/** Channels with a session but no subscription, least recently published to first; under sessions. */
	private final Map<String, Boolean> publishOnly = new LinkedHashMap<>(16, 0.75f, true);
	private final int maxPublishSessions;
	/** Largest message sent or received, in bytes. */
	private final long maxMessageSize;
	private ScheduledFuture<?> pings;

	/**
	 * Creates the instance.
	 *
	 * @param config the configuration
	 */
	public WebSocketInstance(MessagingConfiguration config) {
		super(PROTOCOL, config, FEATURES);
		if (config.uri() == null) {
			throw new MessagingException("A WebSocket instance needs a 'uri'");
		}
		String u = config.uri();
		this.base = URI.create(u.endsWith("/") ? u.substring(0, u.length() - 1) : u);
		this.keepAliveMillis = TimeUnit.SECONDS.toMillis(config.number("keepAliveInterval", 30));
		this.maxPublishSessions = (int) Math.max(1, config.number("maxPublishSessions", 16));
		this.maxMessageSize = config.number("maxMessageSize", 1 << 20);
	}

	// ------------------------------------------------------------------ connection

	private synchronized WebSocketClient client() throws Exception {
		if (client == null) {
			QueuedThreadPool threads = new QueuedThreadPool((int) config.number("maxThreads", 8), 1);
			threads.setName("msg-" + getName() + "-ws");
			HttpClient http = new HttpClient();
			http.setExecutor(threads);
			WebSocketClient c = new WebSocketClient(http);
			c.setIdleTimeout(Duration.ofMillis(3 * keepAliveMillis));
			c.setMaxBinaryMessageSize(maxMessageSize);
			c.setMaxTextMessageSize(maxMessageSize);
			c.start();
			client = c;
		}
		return client;
	}

	@Override
	protected boolean createsChannelsImplicitly() {
		return true;
	}

	@Override
	protected long maxMessageSize() {
		return maxMessageSize;
	}

	@Override
	protected boolean doConnect(long timeoutMillis) throws Exception {
		WebSocketClient c = client();
		c.setConnectTimeout(timeoutMillis);
		control = open(base, null, timeoutMillis);
		pings = scheduler().scheduleWithFixedDelay(this::ping, keepAliveMillis, keepAliveMillis, TimeUnit.MILLISECONDS);
		// the hub keeps no state between connections
		return false;
	}

	@Override
	protected void doDisconnect() {
		if (pings != null) {
			pings.cancel(false);
			pings = null;
		}
		ChannelSession c = control;
		control = null;
		if (c != null) {
			c.close();
		}
		List<ChannelSession> all;
		synchronized (sessions) {
			all = new ArrayList<>(sessions.values());
			sessions.clear();
			publishOnly.clear();
		}
		all.forEach(ChannelSession::close);
	}

	@Override
	public void stop() {
		super.stop();
		WebSocketClient c;
		synchronized (this) {
			c = client;
			client = null;
		}
		if (c != null) {
			try {
				c.stop();
			} catch (Exception e) {
				LOG.log(Level.FINE, "WebSocket client stop failed", e);
			}
		}
	}

	private void ping() {
		ChannelSession c = control;
		if (c != null) {
			c.ping();
		}
		sessions.values().forEach(ChannelSession::ping);
	}

	private ChannelSession open(URI uri, String channel, long timeoutMillis) throws Exception {
		ChannelSession s = new ChannelSession(channel);
		try {
			client().connect(s, uri).get(timeoutMillis, TimeUnit.MILLISECONDS);
		} catch (ExecutionException e) {
			throw e.getCause() instanceof Exception x ? x : e;
		}
		return s;
	}

	/** @return the open session of a channel, connected now if there is none */
	private ChannelSession session(String channel) throws Exception {
		if (control == null) {
			throw new MessagingException("WebSocket instance " + getName() + " is not connected");
		}
		ChannelSession s = sessions.get(channel);
		if (s != null && s.isOpen()) {
			return s;
		}
		synchronized (sessions) {
			s = sessions.get(channel);
			if (s == null || !s.isOpen()) {
				s = open(channelUri(channel), channel, config.connectTimeout());
				sessions.put(channel, s);
			}
			return s;
		}
	}

	private URI channelUri(String channel) throws URISyntaxException {
		for (String segment : channel.split("/", -1)) {
			if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
				throw new MessagingException("A WebSocket channel is a relative path without empty, . or .. segments");
			}
		}
		String path = (base.getPath() == null ? "" : base.getPath()) + "/" + channel;
		// the multi-argument constructor quotes what a path must not contain
		return new URI(base.getScheme(), base.getUserInfo(), base.getHost(), base.getPort(), path, null, null);
	}

	// ------------------------------------------------------------------ publish

	@Override
	protected Promise<Void> doPublish(SimpleMessageContext context, ByteBuffer payload) throws Exception {
		String channel = context.getChannel();
		ChannelSession s = session(channel);
		if (!subscriptions.containsKey(channel)) {
			publishedOnly(channel);
		}
		Deferred<Void> deferred = promises.deferred();
		s.send(payload, Callback.from(() -> deferred.resolve(null), deferred::fail));
		return deferred.getPromise();
	}

	/** Keeps at most maxPublishSessions sessions of channels without subscription open. */
	private void publishedOnly(String channel) {
		List<ChannelSession> evicted = new ArrayList<>();
		synchronized (sessions) {
			publishOnly.put(channel, Boolean.TRUE);
			Iterator<String> eldest = publishOnly.keySet().iterator();
			while (publishOnly.size() > maxPublishSessions) {
				String c = eldest.next();
				eldest.remove();
				if (!subscriptions.containsKey(c)) {
					ChannelSession old = sessions.remove(c);
					if (old != null) {
						evicted.add(old);
					}
				}
			}
		}
		// a normal close: frames already queued on the session still go out
		evicted.forEach(ChannelSession::close);
	}

	/** @return the number of channel sessions, for tests */
	int channelSessions() {
		return sessions.size();
	}

	// ------------------------------------------------------------------ subscribe

	@Override
	protected void doSubscribe(SubscriptionHandle handle) throws Exception {
		String channel = handle.channel();
		subscriptions.computeIfAbsent(channel, c -> new CopyOnWriteArraySet<>()).add(handle);
		synchronized (sessions) {
			publishOnly.remove(channel);
		}
		try {
			session(channel);
		} catch (Exception e) {
			unsubscribeLocal(handle);
			throw e;
		}
	}

	@Override
	protected void doUnsubscribe(SubscriptionHandle handle) {
		if (unsubscribeLocal(handle)) {
			// the last subscription of the channel: its session goes, a publish opens a new one
			ChannelSession s = sessions.remove(handle.channel());
			if (s != null) {
				s.close();
			}
		}
	}

	/** @return {@code true} if it was the last subscription of its channel */
	private boolean unsubscribeLocal(SubscriptionHandle handle) {
		Set<SubscriptionHandle> handles = subscriptions.get(handle.channel());
		if (handles == null) {
			return false;
		}
		synchronized (handles) {
			handles.remove(handle);
			if (handles.isEmpty()) {
				subscriptions.remove(handle.channel(), handles);
				return true;
			}
			return false;
		}
	}

	private void dispatch(String channel, byte[] payload) {
		Set<SubscriptionHandle> handles = subscriptions.get(channel);
		if (handles == null || handles.isEmpty()) {
			return;
		}
		SimpleMessageContext ctx = (SimpleMessageContext) new SimpleMessageContextBuilder().channel(channel).buildContext();
		List<SubscriptionHandle> targets = new ArrayList<>(handles);
		for (int i = 0; i < targets.size(); i++) {
			byte[] own = i == targets.size() - 1 ? payload : payload.clone();
			targets.get(i).deliver(ctx, own, null);
		}
	}

	/**
	 * A WebSocket session of a channel, or of the base URI ({@code channel == null}). Public, as
	 * Jetty calls the listener methods through method handles.
	 */
	public final class ChannelSession implements Session.Listener.AutoDemanding {

		private final String channel;
		private volatile Session session;
		private volatile boolean closing;

		ChannelSession(String channel) {
			this.channel = channel;
		}

		boolean isOpen() {
			Session s = session;
			return s != null && s.isOpen();
		}

		void send(ByteBuffer payload, Callback callback) {
			Session s = session;
			if (s == null || !s.isOpen()) {
				callback.fail(new MessagingException("The WebSocket session of " + channel + " is closed"));
				return;
			}
			s.sendBinary(payload, callback);
		}

		void ping() {
			Session s = session;
			if (s != null && s.isOpen()) {
				s.sendPing(ByteBuffer.allocate(0), Callback.NOOP);
			}
		}

		void close() {
			closing = true;
			Session s = session;
			if (s != null && s.isOpen()) {
				s.close(StatusCode.NORMAL, null, Callback.NOOP);
			}
		}

		@Override
		public void onWebSocketOpen(Session s) {
			session = s;
		}

		@Override
		public void onWebSocketBinary(ByteBuffer payload, Callback callback) {
			byte[] bytes = new byte[payload.remaining()];
			payload.get(bytes);
			callback.succeed();
			if (channel != null) {
				dispatch(channel, bytes);
			}
		}

		@Override
		public void onWebSocketText(String text) {
			if (channel != null) {
				dispatch(channel, text.getBytes(UTF_8));
			}
		}

		@Override
		public void onWebSocketClose(int statusCode, String reason, Callback callback) {
			callback.succeed();
			lost(new MessagingException("WebSocket session of " + (channel == null ? base : channel) + " closed: "
					+ statusCode + (reason == null ? "" : " " + reason)));
		}

		@Override
		public void onWebSocketError(Throwable cause) {
			lost(cause);
		}

		private void lost(Throwable cause) {
			if (closing) {
				return;
			}
			closing = true;
			// an unexpected end of any session: reconnect and restore everything
			if (this == control || (channel != null && sessions.get(channel) == this)) {
				connectionLost(cause);
			}
		}

	}

}
