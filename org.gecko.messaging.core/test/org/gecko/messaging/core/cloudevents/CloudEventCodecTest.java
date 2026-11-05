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
package org.gecko.messaging.core.cloudevents;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.util.Map;

import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.MessagingException;
import org.gecko.messaging.core.SimpleCloudEvent;
import org.gecko.messaging.core.SimpleMessageContextBuilder;
import org.junit.jupiter.api.Test;

class CloudEventCodecTest {

	private final SimpleCloudEvent ce = new SimpleCloudEvent("1", "/src", "type.a", null, "subj",
			OffsetDateTime.parse("2026-11-05T10:00:00Z"), Map.of("tenant", "x", "count", 3L));

	@Test
	void structuredJsonDataStaysJson() {
		MessageContext ctx = new SimpleMessageContextBuilder().contentType("application/json").correlationId("c").replyTo("r").buildContext();
		byte[] env = CloudEventCodec.toStructured(ce, ctx, ByteBuffer.wrap("{\"a\":[1,2,{\"b\":\"\\\"q\"}]}".getBytes(UTF_8)));
		String json = new String(env, UTF_8);
		assertThat(json).contains("\"data\":{\"a\":[1,2,{\"b\":\"\\\"q\"}]}").contains("\"correlationid\":\"c\"").contains("\"count\":3");
		CloudEventCodec.Structured s = CloudEventCodec.fromStructured(env);
		assertThat(s.cloudEvent().getId()).isEqualTo("1");
		assertThat(s.cloudEvent().getTime()).isEqualTo(OffsetDateTime.parse("2026-11-05T10:00:00Z"));
		assertThat(s.cloudEvent().getExtensions()).containsEntry("tenant", "x").containsEntry("count", 3L);
		assertThat(s.correlationId()).isEqualTo("c");
		assertThat(s.replyTo()).isEqualTo("r");
		assertThat(new String(s.data(), UTF_8)).isEqualTo("{\"a\":[1,2,{\"b\":\"\\\"q\"}]}");
	}

	@Test
	void structuredBinaryDataIsBase64AndTextIsString() {
		MessageContext bin = new SimpleMessageContextBuilder().contentType("application/octet-stream").buildContext();
		byte[] env = CloudEventCodec.toStructured(ce, bin, ByteBuffer.wrap(new byte[] { 0, -1, 7 }));
		assertThat(new String(env, UTF_8)).contains("data_base64");
		assertThat(CloudEventCodec.fromStructured(env).data()).containsExactly(0, -1, 7);
		MessageContext txt = new SimpleMessageContextBuilder().contentType("text/plain; charset=utf-8").buildContext();
		byte[] env2 = CloudEventCodec.toStructured(ce, txt, ByteBuffer.wrap("hä\n".getBytes(UTF_8)));
		assertThat(new String(env2, UTF_8)).contains("\"data\":\"hä\\n\"");
		assertThat(new String(CloudEventCodec.fromStructured(env2).data(), UTF_8)).isEqualTo("hä\n");
	}

	@Test
	void invalidJsonDataFallsBackToBase64() {
		MessageContext ctx = new SimpleMessageContextBuilder().contentType("application/json").buildContext();
		byte[] env = CloudEventCodec.toStructured(ce, ctx, ByteBuffer.wrap("{broken".getBytes(UTF_8)));
		assertThat(new String(CloudEventCodec.fromStructured(env).data(), UTF_8)).isEqualTo("{broken");
	}

	@Test
	void binaryHeadersRoundTrip() {
		Map<String, Object> headers = new java.util.LinkedHashMap<>(CloudEventCodec.toBinaryHeaders(ce, "cloudEvents:"));
		headers.put("other", "o");
		CloudEventCodec.Binary b = CloudEventCodec.fromBinaryHeaders(headers, "cloudEvents:");
		assertThat(b.cloudEvent().getType()).isEqualTo("type.a");
		assertThat(b.cloudEvent().getSubject()).isEqualTo("subj");
		assertThat(b.cloudEvent().getExtensions()).containsEntry("tenant", "x");
		assertThat(b.otherHeaders()).containsOnlyKeys("other");
		assertThat(CloudEventCodec.fromBinaryHeaders(Map.of("x", "y"), "ce-").cloudEvent()).isNull();
	}

	@Test
	void rejectsBrokenEnvelopes() {
		assertThatThrownBy(() -> CloudEventCodec.fromStructured("{\"specversion\":\"0.3\"}".getBytes(UTF_8)))
				.isInstanceOf(MessagingException.class);
		assertThatThrownBy(() -> CloudEventCodec.fromStructured("{\"specversion\":\"1.0\",\"id\":\"1\"".getBytes(UTF_8)))
				.isInstanceOf(MessagingException.class);
		assertThat(CloudEventCodec.attributeName("Content-Encoding_1")).isEqualTo("contentencoding1");
	}

	@Test
	void receivedValuesInMessagesAreShortAndOnOneLine() {
		assertThat(CloudEventCodec.printable("a\r\nFAKE log line")).isEqualTo("a??FAKE log line");
		assertThat(CloudEventCodec.printable("x".repeat(1000))).hasSize(67).endsWith("...");
		byte[] envelope = "{\"specversion\":\"9\\r\\nINJECTED\",\"id\":\"1\",\"source\":\"/s\",\"type\":\"t\"}".getBytes(UTF_8);
		assertThatThrownBy(() -> CloudEventCodec.fromStructured(envelope)).isInstanceOf(MessagingException.class)
				.hasMessageNotContaining("\n").hasMessageContaining("??INJECTED");
	}

}
