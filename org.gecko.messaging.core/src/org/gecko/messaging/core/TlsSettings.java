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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.gecko.messaging.api.MessagingException;

/**
 * The TLS side of a connection, the same for every protocol: whether the URI has a TLS scheme, and
 * the key store (client certificate) and trust store (certificate authorities) of the instance.
 * <p>
 * Keys:
 * <ul>
 * <li>{@code tls.keystore} — path of the key store with the client certificate and its key</li>
 * <li>{@code .tls.keystore.password} (or {@code tls.keystore.password}) — its password, also the
 * password of the key</li>
 * <li>{@code tls.truststore} — path of the trust store with the certificates to trust; without it
 * the trust of the JVM applies</li>
 * <li>{@code .tls.truststore.password} (or {@code tls.truststore.password}) — its password</li>
 * <li>{@code tls.keystore.type}, {@code tls.truststore.type} — default {@code PKCS12}</li>
 * </ul>
 * Either store alone is valid. The stores are read once, when the instance is created: a store
 * that cannot be read fails the instance then, not at the first connect. On a URI without TLS
 * scheme the keys are a configuration error — a certificate nobody asks for protects nothing.
 * <p>
 * The context only decides which certificates are presented and trusted. Checking the host name of
 * the broker against its certificate is the protocol's part and stays on.
 */
public final class TlsSettings {

	/** URI schemes over TLS. */
	private static final Set<String> TLS_SCHEMES = Set.of("ssl", "tls", "mqtts", "wss", "amqps", "https");

	private static final String KEYSTORE = "tls.keystore";
	private static final String TRUSTSTORE = "tls.truststore";
	private static final List<String> KEYS = List.of(KEYSTORE, TRUSTSTORE, KEYSTORE + ".password", "." + KEYSTORE + ".password",
			TRUSTSTORE + ".password", "." + TRUSTSTORE + ".password", KEYSTORE + ".type", TRUSTSTORE + ".type");

	private final boolean tls;
	private final SSLContext context;

	private TlsSettings(boolean tls, SSLContext context) {
		this.tls = tls;
		this.context = context;
	}

	/**
	 * Reads the TLS settings and the stores they name.
	 *
	 * @param config the configuration
	 * @return the settings
	 * @throws MessagingException if a store cannot be read or holds nothing usable, or if TLS keys
	 *             are set for a URI without TLS scheme
	 */
	public static TlsSettings of(MessagingConfiguration config) {
		boolean tls = isTls(config.uri());
		String keyStore = config.string(KEYSTORE, null);
		String trustStore = config.string(TRUSTSTORE, null);
		if (!tls) {
			for (String key : KEYS) {
				if (config.string(key, null) != null) {
					throw new MessagingException("'" + key + "' is set, but " + config.displayUri()
							+ " is no TLS connection; use a TLS scheme (ssl, wss, amqps)");
				}
			}
			return new TlsSettings(false, null);
		}
		if (keyStore == null && trustStore == null) {
			if (secret(config, KEYSTORE) != null || secret(config, TRUSTSTORE) != null) {
				throw new MessagingException("A TLS store password is set, but neither '" + KEYSTORE + "' nor '" + TRUSTSTORE + "'");
			}
			return new TlsSettings(true, null);
		}
		try {
			KeyManager[] keys = null;
			if (keyStore != null) {
				char[] password = password(config, KEYSTORE);
				KeyStore store = load("key store", keyStore, config.string(KEYSTORE + ".type", "PKCS12"), password);
				if (!hasEntry(store, true)) {
					throw new MessagingException("The key store " + keyStore + " holds no private key");
				}
				KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
				factory.init(store, password);
				keys = factory.getKeyManagers();
			}
			TrustManager[] trust = null;
			if (trustStore != null) {
				KeyStore store = load("trust store", trustStore, config.string(TRUSTSTORE + ".type", "PKCS12"),
						password(config, TRUSTSTORE));
				if (!hasEntry(store, false)) {
					// would fail every handshake, with a message that does not name the store
					throw new MessagingException("The trust store " + trustStore + " holds no trusted certificate");
				}
				TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
				factory.init(store);
				trust = factory.getTrustManagers();
			}
			SSLContext context = SSLContext.getInstance("TLS");
			// null trust managers: the trust of the JVM
			context.init(keys, trust, null);
			return new TlsSettings(true, context);
		} catch (GeneralSecurityException e) {
			// the exceptions of the key managers may quote what they could not use; the cause stays
			// out of the message, the type says enough
			throw new MessagingException("The TLS stores of " + config.name() + " cannot be used: " + e.getClass().getSimpleName(), e);
		}
	}

	/**
	 * Returns whether a URI has a TLS scheme.
	 *
	 * @param uri the URI, may be {@code null}
	 * @return {@code true} for {@code ssl}, {@code tls}, {@code mqtts}, {@code wss}, {@code amqps}
	 */
	public static boolean isTls(String uri) {
		int colon = uri == null ? -1 : uri.indexOf("://");
		return colon > 0 && TLS_SCHEMES.contains(uri.substring(0, colon).toLowerCase(Locale.ROOT));
	}

	private static String secret(MessagingConfiguration config, String store) {
		// the private property first, as for .password
		return config.string("." + store + ".password", config.string(store + ".password", null));
	}

	private static char[] password(MessagingConfiguration config, String store) {
		String p = secret(config, store);
		return p == null ? null : p.toCharArray();
	}

	private static KeyStore load(String what, String path, String type, char[] password) throws GeneralSecurityException {
		Path file = Path.of(path);
		if (!Files.isReadable(file) || Files.isDirectory(file)) {
			throw new MessagingException("The " + what + " " + path + " cannot be read");
		}
		KeyStore store;
		try {
			store = KeyStore.getInstance(type);
		} catch (GeneralSecurityException e) {
			throw new MessagingException("Unknown type '" + type + "' of the " + what + " " + path, e);
		}
		try (InputStream in = Files.newInputStream(file)) {
			store.load(in, password);
		} catch (IOException e) {
			// a wrong password comes as IOException too
			throw new MessagingException("The " + what + " " + path + " cannot be read as " + type
					+ ": wrong password, wrong type or damaged", e);
		}
		return store;
	}

	private static boolean hasEntry(KeyStore store, boolean key) throws GeneralSecurityException {
		for (String alias : Collections.list(store.aliases())) {
			if (key ? store.isKeyEntry(alias) : store.isCertificateEntry(alias)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns whether the instance connects over TLS.
	 *
	 * @return {@code true} for a URI with a TLS scheme
	 */
	public boolean isTls() {
		return tls;
	}

	/**
	 * Returns the context made of the configured stores.
	 *
	 * @return the context, or {@code null} without a configured store — the protocol then keeps
	 *         its default, the trust of the JVM
	 */
	public SSLContext context() {
		return context;
	}

	/**
	 * Returns the context to connect with over TLS.
	 *
	 * @return the context of the configured stores, or the default context of the JVM
	 * @throws MessagingException if the JVM has no default context
	 */
	public SSLContext contextOrDefault() {
		if (context != null) {
			return context;
		}
		try {
			return SSLContext.getDefault();
		} catch (GeneralSecurityException e) {
			throw new MessagingException("The JVM has no default TLS context", e);
		}
	}

}
