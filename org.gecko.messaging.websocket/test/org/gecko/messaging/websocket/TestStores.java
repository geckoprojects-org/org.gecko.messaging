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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Key and trust stores of a test, made with the {@code keytool} of the running JDK: a server and a
 * client, each with a self-signed certificate for {@code localhost} and a trust store holding the
 * certificate of the other.
 */
final class TestStores {

	static final String PASSWORD = "test-store-secret";

	final Path serverKeys;
	final Path serverTrust;
	final Path clientKeys;
	final Path clientTrust;

	TestStores(Path dir) throws Exception {
		serverKeys = dir.resolve("server.p12");
		serverTrust = dir.resolve("server-trust.p12");
		clientKeys = dir.resolve("client.p12");
		clientTrust = dir.resolve("client-trust.p12");
		Path serverCert = dir.resolve("server.crt");
		Path clientCert = dir.resolve("client.crt");
		keytool("-genkeypair", "-alias", "server", "-keyalg", "EC", "-dname", "CN=localhost", "-ext",
				"SAN=dns:localhost,ip:127.0.0.1", "-validity", "2", "-keystore", serverKeys.toString());
		keytool("-genkeypair", "-alias", "client", "-keyalg", "EC", "-dname", "CN=client", "-validity", "2", "-keystore",
				clientKeys.toString());
		keytool("-exportcert", "-alias", "server", "-keystore", serverKeys.toString(), "-file", serverCert.toString());
		keytool("-exportcert", "-alias", "client", "-keystore", clientKeys.toString(), "-file", clientCert.toString());
		keytool("-importcert", "-noprompt", "-alias", "server", "-file", serverCert.toString(), "-keystore",
				clientTrust.toString());
		keytool("-importcert", "-noprompt", "-alias", "client", "-file", clientCert.toString(), "-keystore",
				serverTrust.toString());
	}

	private static void keytool(String... args) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
		command.addAll(List.of(args));
		command.addAll(List.of("-storetype", "PKCS12", "-storepass", PASSWORD));
		Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = new String(p.getInputStream().readAllBytes());
		if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
			throw new IOException("keytool " + List.of(args) + " failed: " + output);
		}
	}

}
