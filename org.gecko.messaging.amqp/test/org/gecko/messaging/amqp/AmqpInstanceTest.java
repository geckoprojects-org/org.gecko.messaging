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
package org.gecko.messaging.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.gecko.messaging.api.MessagingConstants.*;

import java.nio.ByteBuffer;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.gecko.messaging.api.FeatureNotSupportedException;
import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.MessagingException;
import org.gecko.messaging.core.MessagingConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** What AMQP refuses before anything is sent, without a broker. */
class AmqpInstanceTest {

	private AmqpInstance instance;

	private AmqpInstance instance(Map<String, Object> extra) {
		Map<String, Object> props = new HashMap<>(Map.of(MESSAGING_NAME_PROPERTY, "a", "uri", "amqp://localhost:1"));
		props.putAll(extra);
		instance = new AmqpInstance(new MessagingConfiguration(props));
		return instance;
	}

	@AfterEach
	void stop() {
		if (instance != null) {
			instance.stop();
		}
	}

	@Test
	void exactlyOnceIsAnUnsupportedFeatureUpFront() throws Exception {
		AmqpInstance i = instance(Map.of());
		MessageContext ctx = i.newBuilder().channel("c").extensionEntry(EXTENSION_QOS, QOS_EXACTLY_ONCE).buildContext();
		Message m = i.newBuilder().content(ByteBuffer.wrap(new byte[] { 1 })).buildMessage();
		// refused as feature, not as "not connected", although there is no connection
		assertThat(i.publish(m, ctx).getFailure()).isInstanceOf(FeatureNotSupportedException.class);
		assertThatThrownBy(() -> i.subscribe(ctx)).isInstanceOf(FeatureNotSupportedException.class);
	}

	@Test
	void aQueueHasNoChannelExtension() {
		AmqpInstance i = instance(Map.of());
		MessageContext ctx = i.newBuilder().channel("q", "key").extensionEntry(EXTENSION_CHANNEL_TYPE, CHANNEL_TYPE_QUEUE)
				.buildContext();
		assertThatThrownBy(() -> i.subscribe(ctx)).isInstanceOf(FeatureNotSupportedException.class);
	}

	@Test
	void aDefaultQosOfTwoIsAConfigurationError() {
		assertThatThrownBy(() -> instance(Map.of("qos", 2L))).isInstanceOf(MessagingException.class);
	}

	@Test
	void headerValuesOfOtherAmqpTypesTravelAsText() {
		assertThat(AmqpInstance.headerOf("s")).isEqualTo("s");
		assertThat(AmqpInstance.headerOf(42L)).isEqualTo(42L);
		assertThat(AmqpInstance.headerOf("x".getBytes())).isEqualTo("x");
		assertThat(AmqpInstance.headerOf(new Date(0))).isInstanceOf(String.class);
		assertThat(AmqpInstance.headerOf(Map.of("k", "v"))).isInstanceOf(String.class);
	}

}
