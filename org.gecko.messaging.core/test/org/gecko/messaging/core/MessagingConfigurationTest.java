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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MessagingConfigurationTest {

	private static MessagingConfiguration config(Map<String, Object> props) {
		return new MessagingConfiguration(props);
	}

	@Test
	void byDefaultOnlyGeneratedReplyChannels() {
		MessagingConfiguration c = config(Map.of("osgi.messaging.name", "x"));
		assertThat(c.replyToAllowed("osgi/messaging/reply/other-client/123")).isTrue();
		assertThat(c.replyToAllowed("devices/x/cmd")).as("a channel the sender chose").isFalse();
		MessagingConfiguration own = config(Map.of("osgi.messaging.name", "x", "replyChannelPrefix", "app/replies"));
		assertThat(own.replyToAllowed("app/replies/1")).isTrue();
		assertThat(own.replyToAllowed("osgi/messaging/reply/c/1")).isTrue();
	}

	@Test
	void anyReplyChannelOnlyWhenConfigured() {
		assertThat(config(Map.of("osgi.messaging.name", "x", "replyToAllowed", "*")).replyToAllowed("devices/x/cmd")).isTrue();
	}

	@Test
	void replyToChannelsMustStartWithAnAllowedPrefix() {
		for (Object prefixes : List.of(new String[] { "reply/", "rpc/" }, List.of("reply/", "rpc/"))) {
			MessagingConfiguration c = config(Map.of("osgi.messaging.name", "x", "replyToAllowed", prefixes));
			assertThat(c.replyToAllowed("reply/abc")).isTrue();
			assertThat(c.replyToAllowed("rpc/1")).isTrue();
			assertThat(c.replyToAllowed("devices/x/cmd")).as("a channel the sender chose").isFalse();
		}
		assertThat(config(Map.of("osgi.messaging.name", "x", "replyToAllowed", "reply/")).replyToAllowed("other")).isFalse();
	}

	@Test
	void displayUriHidesTheWholeUserInfo() {
		assertThat(config(Map.of("osgi.messaging.name", "x", "uri", "mqtt://u:p@ss@host:1883")).displayUri())
				.as("an @ in the password").isEqualTo("mqtt://host:1883");
		assertThat(config(Map.of("osgi.messaging.name", "x", "uri", "ws://host/a@b")).displayUri())
				.as("an @ in the path").isEqualTo("ws://host/a@b");
		assertThat(config(Map.of("osgi.messaging.name", "x", "uri", "amqp://u:p@host/vh")).displayUri()).isEqualTo("amqp://host/vh");
	}

	@Test
	void credentialKeysAreSecret() {
		for (String k : new String[] { "password", ".password", "apiKey", "api-key", "accessToken", "client_secret", "credentials" }) {
			assertThat(OsgiBinding.isSecret(k)).as(k).isTrue();
		}
		assertThat(OsgiBinding.isSecret("uri")).isFalse();
		assertThat(OsgiBinding.isSecret("keepAliveInterval")).isFalse();
	}

	@Test
	void prefixesFromOneStringAndTheOwnPrefixAsSubtree() {
		MessagingConfiguration list = config(Map.of("osgi.messaging.name", "x", "replyToAllowed", " a/ , b/ "));
		assertThat(list.replyToAllowed("a/1")).isTrue();
		assertThat(list.replyToAllowed("b/1")).isTrue();
		assertThat(list.replyToAllowed("c/1")).isFalse();
		MessagingConfiguration own = config(Map.of("osgi.messaging.name", "x", "replyChannelPrefix", "app/replies"));
		assertThat(own.replyToAllowed("app/replies/1")).isTrue();
		assertThat(own.replyToAllowed("app/replies-admin/1")).as("not a sibling of the prefix").isFalse();
	}

	@Test
	void wildcardSegmentsAreRecognized() {
		assertThat(Features.hasWildcard("a/+/b")).isTrue();
		assertThat(Features.hasWildcard("a.*.b")).isTrue();
		assertThat(Features.hasWildcard("a/#")).isTrue();
		assertThat(Features.hasWildcard("a/b+c/#d")).as("only whole segments").isFalse();
		assertThat(Features.hasWildcard("a/b")).isFalse();
	}

}
