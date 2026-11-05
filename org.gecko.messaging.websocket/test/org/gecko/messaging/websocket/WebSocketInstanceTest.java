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

import static org.assertj.core.api.Assertions.assertThat;
import static org.gecko.messaging.api.MessagingConstants.MESSAGING_NAME_PROPERTY;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Map;

import org.gecko.messaging.api.MessagingException;
import org.gecko.messaging.api.runtime.ConnectionState;
import org.gecko.messaging.core.MessagingConfiguration;
import org.gecko.messaging.websocket.hub.WebSocketHub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Bounds of the WebSocket instance against a hub in the test. */
class WebSocketInstanceTest {

	private static final int PORT = 18192;

	private final WebSocketHub hub = new WebSocketHub();
	private WebSocketInstance instance;

	@BeforeEach
	void start() throws Exception {
		Method activate = WebSocketHub.class.getDeclaredMethod("activate", WebSocketHub.Config.class);
		activate.setAccessible(true);
		activate.invoke(hub, new WebSocketHub.Config() {
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
				return 64;
			}

			@Override
			public int maxOutgoingFrames() {
				return 64;
			}

			@Override
			public String[] allowedOrigins() {
				return new String[0];
			}
		});
		instance = new WebSocketInstance(new MessagingConfiguration(Map.of(MESSAGING_NAME_PROPERTY, "ws",
				"uri", "ws://localhost:" + PORT + "/m", "maxPublishSessions", 3L, "connectTimeout", 3000L)));
		instance.startStandalone();
		long end = System.currentTimeMillis() + 5000;
		while (instance.getConnectionState() != ConnectionState.CONNECTED && System.currentTimeMillis() < end) {
			Thread.sleep(20);
		}
		assertThat(instance.getConnectionState()).isEqualTo(ConnectionState.CONNECTED);
	}

	@AfterEach
	void stop() throws Exception {
		instance.stop();
		Method deactivate = WebSocketHub.class.getDeclaredMethod("deactivate");
		deactivate.setAccessible(true);
		deactivate.invoke(hub);
	}

	@Test
	void sessionsOfChannelsOnlyPublishedToAreBounded() throws Exception {
		for (int i = 0; i < 20; i++) {
			instance.publish(instance.newBuilder().channel("reply/" + i).content(ByteBuffer.wrap(new byte[] { 1 })).buildMessage(),
					null).getValue();
			assertThat(instance.channelSessions()).isLessThanOrEqualTo(3);
		}
	}

	@Test
	void aChannelCannotLeaveTheBasePath() throws Exception {
		for (String channel : new String[] { "../other", "a/../../b", "a//b", "./a" }) {
			Throwable failure = instance.publish(
					instance.newBuilder().channel(channel).content(ByteBuffer.wrap(new byte[] { 1 })).buildMessage(), null)
					.getFailure();
			assertThat(failure).as(channel).isInstanceOf(MessagingException.class);
		}
	}

}
