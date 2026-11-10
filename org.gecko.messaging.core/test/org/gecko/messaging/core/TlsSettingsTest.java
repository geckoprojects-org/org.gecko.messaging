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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import org.gecko.messaging.api.MessagingException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsSettingsTest {

	@TempDir
	static Path dir;
	static TestStores stores;

	@BeforeAll
	static void makeStores() throws Exception {
		stores = new TestStores(dir);
	}

	private static MessagingConfiguration config(String uri, Map<String, Object> extra) {
		Map<String, Object> props = new HashMap<>(Map.of("osgi.messaging.name", "x", "uri", uri));
		props.putAll(extra);
		return new MessagingConfiguration(props);
	}

	private static Map<String, Object> client(boolean keys, boolean trust) {
		Map<String, Object> props = new HashMap<>();
		if (keys) {
			props.put("tls.keystore", stores.clientKeys.toString());
			props.put(".tls.keystore.password", TestStores.PASSWORD);
		}
		if (trust) {
			props.put("tls.truststore", stores.clientTrust.toString());
			props.put(".tls.truststore.password", TestStores.PASSWORD);
		}
		return props;
	}

	@Test
	void tlsSchemesAreRecognized() {
		for (String uri : new String[] { "ssl://h:8883", "SSL://h", "wss://h/m", "amqps://h/vh", "mqtts://h" }) {
			assertThat(TlsSettings.isTls(uri)).as(uri).isTrue();
		}
		for (String uri : new String[] { "tcp://h:1883", "ws://h/m", "amqp://h", "memory://x", "nonsense", null }) {
			assertThat(TlsSettings.isTls(uri)).as(String.valueOf(uri)).isFalse();
		}
	}

	@Test
	void withoutStoresTheProtocolKeepsItsDefault() {
		TlsSettings tls = TlsSettings.of(config("ssl://localhost:8883", Map.of()));
		assertThat(tls.isTls()).isTrue();
		assertThat(tls.context()).isNull();
		assertThat(tls.contextOrDefault()).isNotNull();
		assertThat(TlsSettings.of(config("tcp://localhost:1883", Map.of())).isTls()).isFalse();
	}

	@Test
	void tlsKeysOnAPlainSchemeAreAnError() {
		for (String key : new String[] { "tls.keystore", "tls.truststore", ".tls.keystore.password", "tls.truststore.type" }) {
			assertThatThrownBy(() -> TlsSettings.of(config("tcp://localhost:1883", Map.of(key, "x")))).as(key)
					.isInstanceOf(MessagingException.class).hasMessageContaining(key).hasMessageContaining("tcp://localhost:1883");
		}
	}

	@Test
	void aStoreThatCannotBeReadFailsWithItsPath() {
		String missing = dir.resolve("missing.p12").toString();
		assertThatThrownBy(() -> TlsSettings.of(config("ssl://h", Map.of("tls.keystore", missing))))
				.isInstanceOf(MessagingException.class).hasMessageContaining(missing);
		assertThatThrownBy(() -> TlsSettings.of(config("ssl://h", Map.of("tls.truststore", dir.toString()))))
				.as("a directory").isInstanceOf(MessagingException.class).hasMessageContaining(dir.toString());
	}

	@Test
	void aWrongPasswordFailsWithThePathAndWithoutThePassword() {
		Map<String, Object> props = client(true, false);
		props.put(".tls.keystore.password", "not-the-password");
		assertThatThrownBy(() -> TlsSettings.of(config("ssl://h", props))).isInstanceOf(MessagingException.class)
				.hasMessageContaining(stores.clientKeys.toString()).hasMessageNotContaining("not-the-password");
	}

	@Test
	void thePrivatePasswordPropertyIsReadFirst() {
		Map<String, Object> props = client(true, false);
		props.put("tls.keystore.password", "not-the-password");
		assertThat(TlsSettings.of(config("ssl://h", props)).context()).isNotNull();
		props.remove(".tls.keystore.password");
		props.put("tls.keystore.password", TestStores.PASSWORD);
		assertThat(TlsSettings.of(config("ssl://h", props)).context()).isNotNull();
	}

	@Test
	void storesWithoutAnythingUsableAreRefused() {
		// a trust store as key store has no private key, a key store as trust store no trusted certificate
		assertThatThrownBy(() -> TlsSettings.of(config("ssl://h",
				Map.of("tls.keystore", stores.clientTrust.toString(), ".tls.keystore.password", TestStores.PASSWORD))))
				.isInstanceOf(MessagingException.class).hasMessageContaining("no private key");
		assertThatThrownBy(() -> TlsSettings.of(config("ssl://h",
				Map.of("tls.truststore", stores.clientKeys.toString(), ".tls.truststore.password", TestStores.PASSWORD))))
				.isInstanceOf(MessagingException.class).hasMessageContaining("no trusted certificate");
		assertThatThrownBy(() -> TlsSettings.of(config("ssl://h", Map.of("tls.keystore", stores.clientKeys.toString(),
				".tls.keystore.password", TestStores.PASSWORD, "tls.keystore.type", "NOPE"))))
				.isInstanceOf(MessagingException.class).hasMessageContaining("NOPE");
		assertThatThrownBy(() -> TlsSettings.of(config("ssl://h", Map.of(".tls.keystore.password", "x"))))
				.as("a password without a store").isInstanceOf(MessagingException.class);
	}

	@Test
	void thePasswordsAreNoServiceProperties() {
		for (String key : new String[] { "tls.keystore.password", ".tls.keystore.password", "tls.truststore.password" }) {
			assertThat(OsgiBinding.isSecret(key)).as(key).isTrue();
		}
		assertThat(OsgiBinding.isSecret("tls.keystore")).as("the path is no secret").isFalse();
	}

	/** A server that demands a client certificate: the handshake decides. */
	@Test
	void theClientCertificateAuthenticatesAgainstAServerDemandingOne() throws Exception {
		SSLContext server = TlsSettings.of(config("ssl://localhost", Map.of("tls.keystore", stores.serverKeys.toString(),
				".tls.keystore.password", TestStores.PASSWORD, "tls.truststore", stores.serverTrust.toString(),
				".tls.truststore.password", TestStores.PASSWORD))).context();
		assertThat(handshake(server, TlsSettings.of(config("ssl://localhost", client(true, true))).context()))
				.as("key store and trust store").isNull();
		assertThat(handshake(server, TlsSettings.of(config("ssl://localhost", client(false, true))).context()))
				.as("without client certificate").isInstanceOf(IOException.class);
		assertThat(handshake(server, TlsSettings.of(config("ssl://localhost", client(true, false))).context()))
				.as("a server the JVM does not trust").isInstanceOf(IOException.class);
	}

	/** @return {@code null} if client and server completed the handshake and exchanged a byte, or the failure */
	private static Throwable handshake(SSLContext server, SSLContext client) throws Exception {
		try (SSLServerSocket listener = (SSLServerSocket) server.getServerSocketFactory().createServerSocket(0)) {
			listener.setNeedClientAuth(true);
			CompletableFuture<Throwable> serverSide = CompletableFuture.supplyAsync(() -> {
				try (SSLSocket s = (SSLSocket) listener.accept()) {
					s.setSoTimeout(5000);
					s.getOutputStream().write(s.getInputStream().read());
					return null;
				} catch (IOException e) {
					return e;
				}
			});
			Throwable clientSide = null;
			try (SSLSocket s = (SSLSocket) client.getSocketFactory().createSocket("localhost", listener.getLocalPort())) {
				s.setSoTimeout(5000);
				SSLParameters p = s.getSSLParameters();
				p.setEndpointIdentificationAlgorithm("HTTPS");
				s.setSSLParameters(p);
				s.getOutputStream().write(42);
				// with TLS 1.3 the client learns of a refused certificate only when it reads
				if (s.getInputStream().read() != 42) {
					clientSide = new IOException("no answer");
				}
			} catch (IOException e) {
				clientSide = e;
			}
			Throwable s = serverSide.get(10, TimeUnit.SECONDS);
			return clientSide != null ? clientSide : s;
		}
	}

}
