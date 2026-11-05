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
import static org.gecko.messaging.api.MessagingConstants.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContext;
import org.junit.jupiter.api.Test;

/**
 * Quality of service, time-to-live and retained messages (MSG140, MSG150, MSG230).
 */
public class DeliveryOptionsTest extends AbstractMessagingTest {

	@Test
	void atLeastOnceIsConfirmed() throws Exception {
		assumeTrue(tck.has(FEATURE_QOS));
		String channel = tck.channel("qos1");
		List<Message> received = collect(builder().channel(channel).extensionEntry(EXTENSION_QOS, QOS_AT_LEAST_ONCE).buildContext());
		MessageContext ctx = builder().channel(channel).extensionEntry(EXTENSION_QOS, QOS_AT_LEAST_ONCE).buildContext();
		publisher.publish(builder().content(Tck.bytes("forward")).buildMessage(), ctx).getValue();
		tck.awaitSize(received, 1);
	}

	@Test
	void atMostOnceIsAlwaysAccepted() throws Exception {
		String channel = tck.channel("qos0");
		List<Message> received = collect(channel);
		MessageContext ctx = builder().channel(channel).extensionEntry(EXTENSION_QOS, QOS_AT_MOST_ONCE).buildContext();
		publisher.publish(builder().content(Tck.bytes("forget")).buildMessage(), ctx).getValue();
		tck.awaitSize(received, 1);
	}

	@Test
	void invalidQosFails() throws Exception {
		MessageContext ctx = builder().channel(tck.channel("qos9")).extensionEntry(EXTENSION_QOS, 9).buildContext();
		assertThat(publisher.publish(builder().content(Tck.bytes("x")).buildMessage(), ctx).getFailure()).isNotNull();
	}

	@Test
	void timeToLiveIsAccepted() throws Exception {
		assumeTrue(tck.has(FEATURE_TIME_TO_LIVE));
		String channel = tck.channel("ttl");
		List<Message> received = collect(channel);
		MessageContext ctx = builder().channel(channel).extensionEntry(EXTENSION_TIME_TO_LIVE, 60_000L).buildContext();
		publisher.publish(builder().content(Tck.bytes("t")).buildMessage(), ctx).getValue();
		tck.awaitSize(received, 1);
	}

	@Test
	void retainedMessageReachesLateSubscribers() throws Exception {
		assumeTrue(tck.has(FEATURE_RETAIN));
		String channel = tck.channel("retained");
		MessageContext ctx = builder().channel(channel).extensionEntry(EXTENSION_RETAIN, true)
				.extensionEntry(EXTENSION_QOS, tck.has(FEATURE_QOS) ? QOS_AT_LEAST_ONCE : QOS_AT_MOST_ONCE).buildContext();
		publisher.publish(builder().content(Tck.bytes("kept")).buildMessage(), ctx).getValue();
		List<Message> late = collect(channel);
		tck.awaitSize(late, 1);
		assertThat(Tck.text(late.get(0))).isEqualTo("kept");
		// clear the retained message again
		publisher.publish(builder().buildMessage(), ctx).getValue();
	}

}
