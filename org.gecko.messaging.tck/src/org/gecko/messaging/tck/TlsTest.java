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
package org.gecko.messaging.tck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessagePublisher;
import org.gecko.messaging.api.MessageSubscription;
import org.gecko.messaging.api.runtime.ConnectionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Mutual TLS (§2.2.1): a sibling of the instance under test connects to a broker that demands a
 * client certificate — with the key store and trust store of the run it connects, without the
 * client certificate the broker refuses it, without the trust store the instance refuses the
 * broker.
 * <p>
 * Runs only with the framework properties {@code org.gecko.messaging.tck.tls.uri} (the URI of such
 * a broker, with a TLS scheme), {@code ...tls.keystore} (a client certificate the broker accepts),
 * {@code ...tls.truststore} (the certificate authority of the broker, one the JVM does not trust
 * by itself) and {@code ...tls.password} (the password of both stores), and with the
 * configuration of the instance under test in Configuration Admin.
 */
public class TlsTest extends AbstractMessagingTest {

	private String uri;
	private String keyStore;
	private String trustStore;
	private String password;
	private Sibling sibling;

	@BeforeEach
	void needsATlsBroker() {
		uri = bundleContext.getProperty(Tck.PREFIX + "tls.uri");
		assumeTrue(uri != null && !uri.isBlank(), "no broker demanding a client certificate in this TCK run");
		keyStore = bundleContext.getProperty(Tck.PREFIX + "tls.keystore");
		trustStore = bundleContext.getProperty(Tck.PREFIX + "tls.truststore");
		password = bundleContext.getProperty(Tck.PREFIX + "tls.password");
		assertThat(List.of(String.valueOf(keyStore), String.valueOf(trustStore), String.valueOf(password)))
				.as(Tck.PREFIX + "tls.keystore, .truststore and .password").doesNotContain("null");
	}

	@AfterEach
	void removeSibling() throws Exception {
		if (sibling != null) {
			sibling.close();
			sibling = null;
		}
	}

	private Map<String, Object> configuration(boolean clientCertificate, boolean trust) {
		Map<String, Object> props = new HashMap<>();
		props.put("uri", uri);
		if (clientCertificate) {
			props.put("tls.keystore", keyStore);
			props.put(".tls.keystore.password", password);
		}
		if (trust) {
			props.put("tls.truststore", trustStore);
			props.put(".tls.truststore.password", password);
		}
		return props;
	}

	/** Gives a connection that must not come about the time of several attempts. */
	private void assertNeverConnected(String why) throws InterruptedException {
		long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(3000, tck.timeout() / 3));
		while (System.nanoTime() < end) {
			assertThat(sibling.state()).as(why).isNotEqualTo(ConnectionState.CONNECTED);
			Thread.sleep(50);
		}
	}

	@Test
	void aClientCertificateAuthenticatesTheInstance() throws Exception {
		sibling = new Sibling(tck, "mutual", configuration(true, true));
		sibling.awaitConnected();
		String channel = tck.channel("tls");
		List<Message> received = collect(sibling.service(MessageSubscription.class).subscribe(channel));
		tck.settle();
		assertThat(sibling.service(MessagePublisher.class).publish(text(channel, "over mutual TLS")).getFailure()).isNull();
		tck.awaitSize(received, 1);
		assertThat(received).extracting(Tck::text).containsExactly("over mutual TLS");
	}

	@Test
	void theStorePasswordsAreNoServiceProperties() throws Exception {
		sibling = new Sibling(tck, "mutual", configuration(true, true));
		for (String key : sibling.runtimeReference().getPropertyKeys()) {
			assertThat(key.toLowerCase(Locale.ROOT)).as("service property").doesNotContain("password");
			assertThat(String.valueOf(sibling.runtimeReference().getProperty(key))).as(key).isNotEqualTo(password);
		}
	}

	@Test
	void withoutAClientCertificateTheBrokerRefusesTheInstance() throws Exception {
		sibling = new Sibling(tck, "nocert", configuration(false, true));
		assertNeverConnected("a broker demanding a client certificate, an instance without one");
	}

	@Test
	void aBrokerOfAnUnknownAuthorityIsNotTrusted() throws Exception {
		sibling = new Sibling(tck, "notrust", configuration(true, false));
		assertNeverConnected("the certificate authority of the broker is not in the trust of the JVM");
	}

}
