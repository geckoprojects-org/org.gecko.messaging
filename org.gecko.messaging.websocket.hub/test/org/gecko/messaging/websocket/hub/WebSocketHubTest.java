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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.Annotation;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebSocketHubTest {

	private static final int PORT = 18190;

	private final WebSocketHub hub = new WebSocketHub();
	private final WebSocketClient client = new WebSocketClient();

	/** A listener that collects received binary messages. */
	public static final class Collector implements Session.Listener.AutoDemanding {

		final BlockingQueue<String> received = new LinkedBlockingQueue<>();

		@Override
		public void onWebSocketBinary(ByteBuffer payload, Callback callback) {
			byte[] b = new byte[payload.remaining()];
			payload.get(b);
			received.add(new String(b));
			callback.succeed();
		}

	}

	@BeforeEach
	void start() throws Exception {
		hub.activate(new WebSocketHub.Config() {
			@Override
			public Class<? extends Annotation> annotationType() {
				return WebSocketHub.Config.class;
			}

			@Override
			public String host() {
				return "localhost";
			}

			@Override
			public int port() {
				return PORT;
			}

			@Override
			public long idleTimeout() {
				return 30;
			}

			@Override
			public long maxMessageSize() {
				return 1 << 16;
			}

			@Override
			public int maxThreads() {
				return 8;
			}

			@Override
			public int maxConnections() {
				return 16;
			}

			@Override
			public int maxOutgoingFrames() {
				return 8;
			}

			@Override
			public String[] allowedOrigins() {
				return new String[] { "https://allowed.example" };
			}
		});
		client.start();
	}

	@AfterEach
	void stop() throws Exception {
		client.stop();
		hub.deactivate();
	}

	private Session connect(Object listener, String origin) throws Exception {
		ClientUpgradeRequest request = new ClientUpgradeRequest(URI.create("ws://localhost:" + PORT + "/a/b"));
		if (origin != null) {
			request.setHeader("Origin", origin);
		}
		return client.connect(listener, request).get(5, TimeUnit.SECONDS);
	}

	@Test
	void relaysToEverySessionOfThePath() throws Exception {
		Collector a = new Collector();
		Collector b = new Collector();
		Session sa = connect(a, null);
		connect(b, null);
		sa.sendBinary(ByteBuffer.wrap("hello".getBytes()), Callback.NOOP);
		assertThat(a.received.poll(5, TimeUnit.SECONDS)).as("the sender too").isEqualTo("hello");
		assertThat(b.received.poll(5, TimeUnit.SECONDS)).isEqualTo("hello");
	}

	@Test
	void browserPagesNeedAnAllowedOrigin() throws Exception {
		assertThatThrownBy(() -> connect(new Collector(), "https://evil.example")).isInstanceOf(ExecutionException.class)
				.hasMessageContaining("403");
		assertThat(connect(new Collector(), "https://allowed.example").isOpen()).isTrue();
	}

}
