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
import java.util.Map;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContext;
import org.junit.jupiter.api.Test;

/**
 * Message properties travel with the message (MSG210, MSG220) and contexts behave as specified.
 */
public class MessageContextTest extends AbstractMessagingTest {

	@Test
	void contentTypeAndEncodingTravel() throws Exception {
		assumeTrue(tck.has(FEATURE_CONTENT_TYPE));
		String channel = tck.channel("ct");
		List<Message> received = collect(channel);
		publisher.publish(builder().channel(channel).contentType("application/json").contentEncoding("identity")
				.content(Tck.bytes("{}")).buildMessage()).getValue();
		tck.awaitSize(received, 1);
		assertThat(received.get(0).getContext().getContentType()).isEqualTo("application/json");
		assertThat(received.get(0).getContext().getContentEncoding()).isEqualTo("identity");
	}

	@Test
	void headersTravel() throws Exception {
		assumeTrue(tck.has(FEATURE_HEADERS));
		String channel = tck.channel("headers");
		List<Message> received = collect(channel);
		publisher.publish(builder().channel(channel).header("tenant", "acme").header("count", 7).header("flag", true)
				.content(Tck.bytes("h")).buildMessage()).getValue();
		tck.awaitSize(received, 1);
		Map<String, Object> headers = received.get(0).getContext().getHeaders();
		assertThat(headers).containsEntry("tenant", "acme");
		// protocols that carry strings only deliver every value as String
		assertThat(String.valueOf(headers.get("count"))).isEqualTo("7");
		assertThat(String.valueOf(headers.get("flag"))).isEqualTo("true");
	}

	@Test
	void correlationIdAndReplyToTravel() throws Exception {
		assumeTrue(tck.has(FEATURE_REPLY_TO));
		String channel = tck.channel("corr");
		List<Message> received = collect(channel);
		String replyTo = tck.channel("corr", "reply");
		publisher.publish(builder().channel(channel).correlationId("c-42").replyTo(replyTo).content(Tck.bytes("r")).buildMessage())
				.getValue();
		tck.awaitSize(received, 1);
		assertThat(received.get(0).getContext().getCorrelationId()).isEqualTo("c-42");
		assertThat(received.get(0).getContext().getReplyToChannel()).isEqualTo(replyTo);
	}

	@Test
	void contextsAreImmutableAndBuildersReset() {
		var b = builder().channel("a").header("k", "v").extensionEntry("x", 1);
		MessageContext first = b.buildContext();
		MessageContext second = b.buildContext();
		assertThat(first.getChannel()).isEqualTo("a");
		assertThat(second.getChannel()).as("the builder is reset after build").isNull();
		assertThat(second.getHeaders()).isEmpty();
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> first.getHeaders().put("y", "z"))
				.isInstanceOf(UnsupportedOperationException.class);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> first.getExtensions().put("y", "z"))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void withContextStartsFromAnotherContext() {
		MessageContext base = builder().channel("c", "e").contentType("text/plain").correlationId("id").buildContext();
		MessageContext derived = builder().withContext(base).correlationId("other").buildContext();
		assertThat(derived.getChannel()).isEqualTo("c");
		assertThat(derived.getChannelExtension()).isEqualTo("e");
		assertThat(derived.getContentType()).isEqualTo("text/plain");
		assertThat(derived.getCorrelationId()).isEqualTo("other");
	}

	@Test
	void payloadBuffersAreIndependent() {
		java.nio.ByteBuffer source = Tck.bytes("abc");
		Message m = builder().channel("c").content(source).buildMessage();
		source.put(0, (byte) 'z');
		m.payload().get();
		assertThat(Tck.text(m)).isEqualTo("abc");
	}

}
