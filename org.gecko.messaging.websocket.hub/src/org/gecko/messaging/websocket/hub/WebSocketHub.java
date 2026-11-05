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
package org.gecko.messaging.websocket.hub;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.NetworkConnectionLimit;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * A WebSocket hub: every message received on a URL path goes to all sessions of that path, the
 * sender included, as the same frame type. The counterpart of the WebSocket messaging instances,
 * whose channels are the paths. One hub per factory configuration
 * {@code org.gecko.messaging.websocket.hub}.
 * <p>
 * The hub keeps nothing: a message reaches the sessions open at that moment. It has no
 * authentication and binds to localhost unless {@code host} says otherwise. Limits against a
 * peer that floods or does not read: message size, connections, and frames queued for a session
 * — a session that falls that far behind is closed. A browser page could reach a hub on
 * localhost as well, so an upgrade carrying an {@code Origin} is refused unless the origin is
 * allowed; WebSocket clients outside a browser send none.
 */
@Component(name = "org.gecko.messaging.websocket.hub", configurationPolicy = ConfigurationPolicy.REQUIRE, service = {})
@Designate(ocd = WebSocketHub.Config.class, factory = true)
public class WebSocketHub {

	/** The configuration of a hub. */
	@ObjectClassDefinition(name = "Gecko Messaging WebSocket Hub")
	public @interface Config {

		/** @return the interface to bind to */
		@AttributeDefinition(description = "Interface to bind to; 0.0.0.0 for all")
		String host() default "localhost";

		/** @return the port */
		@AttributeDefinition(description = "Port; 0 for any free one")
		int port() default 8090;

		/** @return the idle timeout */
		@AttributeDefinition(description = "Idle timeout of a session in s; the clients ping more often")
		long idleTimeout() default 120;

		/** @return the largest message */
		@AttributeDefinition(description = "Largest message in bytes")
		long maxMessageSize() default 1 << 20;

		/** @return the server threads */
		@AttributeDefinition(description = "Most server threads")
		int maxThreads() default 16;

		/** @return the most connections */
		@AttributeDefinition(description = "Most open connections")
		int maxConnections() default 256;

		/** @return the most frames queued for one session */
		@AttributeDefinition(description = "Most frames queued for one session; a session behind that is closed")
		int maxOutgoingFrames() default 64;

		/** @return the origins a browser page may connect from */
		@AttributeDefinition(description = "Origins a browser page may connect from; none by default")
		String[] allowedOrigins() default {};

	}

	/** Members by decoded path; joining and leaving are atomic per path. */
	private final Map<String, Set<Member>> paths = new ConcurrentHashMap<>();
	private Server server;

	@Activate
	void activate(Config config) throws Exception {
		QueuedThreadPool threads = new QueuedThreadPool(config.maxThreads(), 2);
		threads.setName("msg-ws-hub-" + config.port());
		Server s = new Server(threads);
		ServerConnector connector = new ServerConnector(s);
		connector.setHost(config.host());
		connector.setPort(config.port());
		s.addConnector(connector);
		s.addBean(new NetworkConnectionLimit(config.maxConnections(), s));
		Set<String> origins = Set.of(config.allowedOrigins());
		s.setHandler(WebSocketUpgradeHandler.from(s, container -> {
			container.setIdleTimeout(Duration.ofSeconds(config.idleTimeout()));
			container.setMaxBinaryMessageSize(config.maxMessageSize());
			container.setMaxTextMessageSize(config.maxMessageSize());
			container.setMaxOutgoingFrames(config.maxOutgoingFrames());
			container.addMapping("/*", (request, response, callback) -> {
				String origin = request.getHeaders().get(HttpHeader.ORIGIN);
				if (origin != null && !origins.contains(origin)) {
					Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403);
					return null;
				}
				return new Member(request.getHttpURI().getDecodedPath());
			});
		}));
		s.start();
		server = s;
	}

	@Deactivate
	void deactivate() throws Exception {
		Server s = server;
		server = null;
		if (s != null) {
			s.stop();
		}
		paths.clear();
	}

	/** One session of a path. Public, as Jetty calls the listener methods through method handles. */
	public final class Member implements Session.Listener.AutoDemanding {

		private final String path;
		private volatile Session session;
		/** Closes this session when a frame to it cannot be queued: it does not keep up. */
		private final Callback sent = Callback.from(() -> {
		}, failure -> close(StatusCode.POLICY_VIOLATION, "Not reading fast enough"));

		Member(String path) {
			this.path = path;
		}

		@Override
		public void onWebSocketOpen(Session s) {
			session = s;
			paths.compute(path, (p, members) -> {
				Set<Member> m = members == null ? ConcurrentHashMap.newKeySet() : members;
				m.add(this);
				return m;
			});
		}

		@Override
		public void onWebSocketBinary(ByteBuffer payload, Callback callback) {
			// one copy for every receiver: the frame buffer goes back to Jetty right away
			byte[] bytes = new byte[payload.remaining()];
			payload.get(bytes);
			callback.succeed();
			for (Member m : members()) {
				Session s = m.session;
				if (s != null) {
					s.sendBinary(ByteBuffer.wrap(bytes), m.sent);
				}
			}
		}

		@Override
		public void onWebSocketText(String text) {
			for (Member m : members()) {
				Session s = m.session;
				if (s != null) {
					s.sendText(text, m.sent);
				}
			}
		}

		private Set<Member> members() {
			Set<Member> members = paths.get(path);
			return members == null ? Set.of() : members;
		}

		private void close(int status, String reason) {
			Session s = session;
			if (s != null && s.isOpen()) {
				s.close(status, reason, Callback.NOOP);
			}
		}

		@Override
		public void onWebSocketClose(int statusCode, String reason, Callback callback) {
			callback.succeed();
			leave();
		}

		@Override
		public void onWebSocketError(Throwable cause) {
			leave();
		}

		private void leave() {
			paths.computeIfPresent(path, (p, members) -> {
				members.remove(this);
				return members.isEmpty() ? null : members;
			});
		}

	}

}
