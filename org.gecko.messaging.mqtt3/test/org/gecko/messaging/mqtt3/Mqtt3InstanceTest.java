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
package org.gecko.messaging.mqtt3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.gecko.messaging.api.MessagingConstants.*;

import java.util.Map;

import org.gecko.messaging.api.MessagingException;
import org.gecko.messaging.core.MessagingConfiguration;
import org.junit.jupiter.api.Test;

/** What an MQTT 3.1.1 instance announces, without a broker. */
class Mqtt3InstanceTest {

	private static Mqtt3Instance instance(String cloudEvents) {
		return new Mqtt3Instance(new MessagingConfiguration(
				Map.of(MESSAGING_NAME_PROPERTY, "m3", "uri", "tcp://localhost:1", "cloudEvents", cloudEvents)));
	}

	@Test
	void binaryCloudEventsAreRefusedAtStartNotPerMessage() {
		Mqtt3Instance i = instance("binary");
		assertThatThrownBy(i::startStandalone).isInstanceOf(MessagingException.class).hasMessageContaining("structured");
		i.stop();
	}

	@Test
	void theStructuredEnvelopeAddsWhatMqtt3Lacks() {
		assertThat(instance("none").getFeatures().asSet()).doesNotContain(FEATURE_HEADERS, FEATURE_REPLY_TO);
		assertThat(instance("structured").getFeatures().asSet()).contains(FEATURE_CLOUD_EVENTS, FEATURE_CONTENT_TYPE,
				FEATURE_HEADERS, FEATURE_REPLY_TO, FEATURE_REPLY_TO_MANY, FEATURE_GENERATE_CORRELATION_ID);
	}

}
