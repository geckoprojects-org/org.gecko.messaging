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
	void anyReplyToChannelWithoutPrefixes() {
		assertThat(config(Map.of("osgi.messaging.name", "x")).replyToAllowed("devices/x/cmd")).isTrue();
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
			assertThat(AbstractMessagingInstance.isSecret(k)).as(k).isTrue();
		}
		assertThat(AbstractMessagingInstance.isSecret("uri")).isFalse();
		assertThat(AbstractMessagingInstance.isSecret("keepAliveInterval")).isFalse();
	}

}
