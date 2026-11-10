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

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.runtime.ConnectionState;
import org.gecko.messaging.core.MessagingConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code wss} with the TLS stores of the instance, against a server that demands a client certificate. */
public class WebSocketTlsTest {

	@TempDir
	static Path dir;
	static TestStores stores;
	static Server server;
	static int port;

	private WebSocketInstance instance;

	/** Sends every message back. Public, as Jetty calls the listener methods through method handles. */
	public static final class Echo implements Session.Listener.AutoDemanding {

		private volatile Session session;

		@Override
		public void onWebSocketOpen(Session s) {
			session = s;
		}

		@Override
		public void onWebSocketBinary(ByteBuffer payload, Callback callback) {
			byte[] bytes = new byte[payload.remaining()];
			payload.get(bytes);
			callback.succeed();
			session.sendBinary(ByteBuffer.wrap(bytes), Callback.NOOP);
		}

	}

	@BeforeAll
	static void startServer() throws Exception {
		stores = new TestStores(dir);
		SslContextFactory.Server ssl = new SslContextFactory.Server();
		ssl.setKeyStorePath(stores.serverKeys.toString());
		ssl.setKeyStoreType("PKCS12");
		ssl.setKeyStorePassword(TestStores.PASSWORD);
		ssl.setTrustStorePath(stores.serverTrust.toString());
		ssl.setTrustStoreType("PKCS12");
		ssl.setTrustStorePassword(TestStores.PASSWORD);
		ssl.setNeedClientAuth(true);
		Server s = new Server();
		ServerConnector connector = new ServerConnector(s, ssl);
		connector.setHost("localhost");
		s.addConnector(connector);
		s.setHandler(WebSocketUpgradeHandler.from(s,
				container -> container.addMapping("/*", (request, response, callback) -> new Echo())));
		s.start();
		server = s;
		port = connector.getLocalPort();
	}

	@AfterAll
	static void stopServer() throws Exception {
		if (server != null) {
			server.stop();
		}
	}

	@AfterEach
	void stop() {
		if (instance != null) {
			instance.stop();
		}
	}

	private void start(boolean clientCertificate, boolean trust) {
		Map<String, Object> props = new HashMap<>(Map.of(MESSAGING_NAME_PROPERTY, "wss", "uri", "wss://localhost:" + port + "/m",
				"connectTimeout", 3000L, "reconnectDelay", 100L, "reconnectMaxDelay", 200L));
		if (clientCertificate) {
			props.put("tls.keystore", stores.clientKeys.toString());
			props.put(".tls.keystore.password", TestStores.PASSWORD);
		}
		if (trust) {
			props.put("tls.truststore", stores.clientTrust.toString());
			props.put(".tls.truststore.password", TestStores.PASSWORD);
		}
		instance = new WebSocketInstance(new MessagingConfiguration(props));
		instance.startStandalone();
	}

	private boolean connectsWithin(long millis) throws InterruptedException {
		long end = System.currentTimeMillis() + millis;
		while (System.currentTimeMillis() < end) {
			if (instance.getConnectionState() == ConnectionState.CONNECTED) {
				return true;
			}
			Thread.sleep(20);
		}
		return false;
	}

	@Test
	void aClientCertificateAuthenticatesTheInstance() throws Exception {
		start(true, true);
		assertThat(connectsWithin(5000)).isTrue();
		List<Message> received = new CopyOnWriteArrayList<>();
		instance.subscribe("a").forEach(received::add);
		assertThat(instance.publish(instance.newBuilder().channel("a").content(ByteBuffer.wrap(new byte[] { 7 })).buildMessage(),
				null).getFailure()).isNull();
		long end = System.currentTimeMillis() + 5000;
		while (received.isEmpty() && System.currentTimeMillis() < end) {
			Thread.sleep(20);
		}
		assertThat(received).hasSize(1);
		assertThat(received.get(0).payload().get(0)).isEqualTo((byte) 7);
	}

	@Test
	void withoutAClientCertificateTheServerRefusesTheInstance() throws Exception {
		start(false, true);
		assertThat(connectsWithin(1500)).isFalse();
	}

	@Test
	void aServerOfAnUnknownAuthorityIsNotTrusted() throws Exception {
		start(true, false);
		assertThat(connectsWithin(1500)).isFalse();
	}

}
