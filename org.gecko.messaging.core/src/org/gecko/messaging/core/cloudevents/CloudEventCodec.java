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
import static org.gecko.messaging.api.cloudevents.CloudEventConstants.EXTENSION_CONTENT_ENCODING;
import static org.gecko.messaging.api.cloudevents.CloudEventConstants.EXTENSION_CORRELATION_ID;
import static org.gecko.messaging.api.cloudevents.CloudEventConstants.EXTENSION_REPLY_TO;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.MessagingException;
import org.gecko.messaging.api.cloudevents.CloudEvent;
import org.gecko.messaging.api.cloudevents.CloudEventConstants;
import org.gecko.messaging.core.SimpleCloudEvent;

/**
 * Encodes and decodes CloudEvents in the binary and structured content modes.
 */
public final class CloudEventCodec {

	/** The context attributes with their own place in a CloudEvent. */
	private static final Set<String> CORE = Set.of("specversion", "id", "source", "type", "subject", "dataschema", "time",
			"datacontenttype", "data", "data_base64");

	private CloudEventCodec() {
		// static only
	}

	/**
	 * The decoded content of a structured CloudEvent.
	 *
	 * @param cloudEvent the attributes
	 * @param contentType {@code datacontenttype}, or {@code null}
	 * @param correlationId the {@code correlationid} extension, or {@code null}
	 * @param replyTo the {@code replyto} extension, or {@code null}
	 * @param contentEncoding the {@code contentencoding} extension, or {@code null}
	 * @param data the data bytes
	 */
	public record Structured(CloudEvent cloudEvent, String contentType, String correlationId, String replyTo,
			String contentEncoding, byte[] data) {
	}

	/**
	 * The decoded binary CloudEvent: the attributes and the headers that are no attributes.
	 *
	 * @param cloudEvent the attributes, {@code null} if the headers carry no CloudEvent
	 * @param otherHeaders the remaining headers
	 */
	public record Binary(CloudEvent cloudEvent, Map<String, Object> otherHeaders) {
	}

	/**
	 * Returns a valid CloudEvents attribute name for a header name: lower case letters and digits.
	 *
	 * @param name the header name
	 * @return the attribute name
	 */
	public static String attributeName(String name) {
		StringBuilder b = new StringBuilder(name.length());
		for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
				b.append(c);
			}
		}
		if (b.isEmpty()) {
			throw new MessagingException("'" + name + "' gives no valid CloudEvents attribute name");
		}
		return b.toString();
	}

	/**
	 * Returns the attributes of a CloudEvent as protocol headers (binary mode). The
	 * {@code datacontenttype} is left out: it travels as the content type of the protocol.
	 *
	 * @param ce the CloudEvent
	 * @param prefix the header prefix of the protocol binding, e.g. {@code cloudEvents:}
	 * @return the headers
	 */
	public static Map<String, Object> toBinaryHeaders(CloudEvent ce, String prefix) {
		Map<String, Object> h = new LinkedHashMap<>();
		h.put(prefix + "specversion", ce.getSpecVersion());
		h.put(prefix + "id", ce.getId());
		h.put(prefix + "source", ce.getSource());
		h.put(prefix + "type", ce.getType());
		putIfSet(h, prefix + "subject", ce.getSubject());
		putIfSet(h, prefix + "dataschema", ce.getDataSchema());
		if (ce.getTime() != null) {
			h.put(prefix + "time", ce.getTime().toString());
		}
		ce.getExtensions().forEach((k, v) -> h.put(prefix + attributeName(k), v));
		return h;
	}

	/**
	 * Reads a CloudEvent from protocol headers (binary mode).
	 *
	 * @param headers the headers of the received message
	 * @param prefix the header prefix of the protocol binding
	 * @return the decoded event; its {@code cloudEvent} is {@code null} if there is none
	 */
	public static Binary fromBinaryHeaders(Map<String, Object> headers, String prefix) {
		Object specversion = headers.get(prefix + "specversion");
		if (specversion == null) {
			return new Binary(null, headers);
		}
		Map<String, Object> ext = new LinkedHashMap<>();
		Map<String, Object> other = new LinkedHashMap<>();
		for (Map.Entry<String, Object> e : headers.entrySet()) {
			String k = e.getKey();
			if (k.startsWith(prefix)) {
				String attr = k.substring(prefix.length());
				if (!CORE.contains(attr)) {
					ext.put(attr, e.getValue());
				}
			} else {
				other.put(k, e.getValue());
			}
		}
		CloudEvent ce = new SimpleCloudEvent(str(headers.get(prefix + "id")), str(headers.get(prefix + "source")),
				str(headers.get(prefix + "type")), str(headers.get(prefix + "dataschema")), str(headers.get(prefix + "subject")),
				time(headers.get(prefix + "time")), ext);
		if (ce.getId() == null || ce.getSource() == null || ce.getType() == null) {
			throw new MessagingException("A binary CloudEvent misses id, source or type: " + headers.keySet());
		}
		return new Binary(ce, other);
	}

	/**
	 * Encodes a CloudEvent with its data as JSON envelope (structured mode).
	 *
	 * @param ce the CloudEvent
	 * @param context the message context: content type, correlation id, reply-to, encoding
	 * @param data the data
	 * @return the envelope, UTF-8
	 */
	public static byte[] toStructured(CloudEvent ce, MessageContext context, ByteBuffer data) {
		StringBuilder b = new StringBuilder(256 + data.remaining());
		b.append('{');
		member(b, "specversion", ce.getSpecVersion());
		member(b, "id", ce.getId());
		member(b, "source", ce.getSource());
		member(b, "type", ce.getType());
		member(b, "subject", ce.getSubject());
		member(b, "dataschema", ce.getDataSchema());
		member(b, "time", ce.getTime() == null ? null : ce.getTime().toString());
		String contentType = context.getContentType();
		member(b, "datacontenttype", contentType);
		member(b, EXTENSION_CORRELATION_ID, context.getCorrelationId());
		member(b, EXTENSION_REPLY_TO, context.getReplyToChannel());
		member(b, EXTENSION_CONTENT_ENCODING, context.getContentEncoding());
		for (Map.Entry<String, Object> e : ce.getExtensions().entrySet()) {
			String name = attributeName(e.getKey());
			if (CORE.contains(name)) {
				continue;
			}
			Object v = e.getValue();
			if (v instanceof Number || v instanceof Boolean) {
				comma(b);
				Json.quote(b, name);
				b.append(':').append(v);
			} else {
				member(b, name, v == null ? null : v.toString());
			}
		}
		if (data.hasRemaining()) {
			byte[] bytes = new byte[data.remaining()];
			data.duplicate().get(bytes);
			String text = isJson(contentType) || isText(contentType) ? utf8(bytes) : null;
			comma(b);
			if (text != null && isJson(contentType) && Json.isValue(text)) {
				b.append("\"data\":").append(text.strip());
			} else if (text != null && isText(contentType)) {
				Json.quote(b, "data");
				b.append(':');
				Json.quote(b, text);
			} else {
				b.append("\"data_base64\":\"").append(Base64.getEncoder().encodeToString(bytes)).append('"');
			}
		}
		b.append('}');
		return b.toString().getBytes(UTF_8);
	}

	/**
	 * Decodes a JSON envelope (structured mode).
	 *
	 * @param envelope the envelope bytes
	 * @return the decoded event
	 * @throws MessagingException if the envelope is no valid CloudEvent
	 */
	public static Structured fromStructured(byte[] envelope) {
		Map<String, Object> m;
		try {
			m = Json.parseObject(new String(envelope, UTF_8));
		} catch (IllegalArgumentException e) {
			throw new MessagingException("No valid CloudEvents JSON envelope: " + e.getMessage(), e);
		}
		if (!CloudEventConstants.SPEC_VERSION.equals(str(m.get("specversion")))) {
			throw new MessagingException("Unsupported CloudEvents specversion " + m.get("specversion"));
		}
		Map<String, Object> ext = new LinkedHashMap<>();
		for (Map.Entry<String, Object> e : m.entrySet()) {
			String k = e.getKey();
			if (!CORE.contains(k) && !EXTENSION_CORRELATION_ID.equals(k) && !EXTENSION_REPLY_TO.equals(k)
					&& !EXTENSION_CONTENT_ENCODING.equals(k) && e.getValue() != null) {
				Object v = e.getValue();
				ext.put(k, v instanceof Json.Raw r ? r.text() : v instanceof BigDecimal d ? d.doubleValue() : v);
			}
		}
		SimpleCloudEvent ce = new SimpleCloudEvent(str(m.get("id")), str(m.get("source")), str(m.get("type")),
				str(m.get("dataschema")), str(m.get("subject")), time(m.get("time")), ext);
		if (ce.getId() == null || ce.getSource() == null || ce.getType() == null) {
			throw new MessagingException("A CloudEvent misses id, source or type");
		}
		byte[] data;
		Object d = m.get("data");
		Object d64 = m.get("data_base64");
		if (d64 != null) {
			data = Base64.getDecoder().decode(d64.toString());
		} else if (d instanceof Json.Raw r) {
			data = r.text().getBytes(UTF_8);
		} else if (d instanceof String s) {
			data = isJson(str(m.get("datacontenttype"))) ? quoted(s) : s.getBytes(UTF_8);
		} else if (d != null) {
			data = d.toString().getBytes(UTF_8);
		} else {
			data = new byte[0];
		}
		return new Structured(ce, str(m.get("datacontenttype")), str(m.get(EXTENSION_CORRELATION_ID)),
				str(m.get(EXTENSION_REPLY_TO)), str(m.get(EXTENSION_CONTENT_ENCODING)), data);
	}

	private static byte[] quoted(String s) {
		StringBuilder b = new StringBuilder();
		Json.quote(b, s);
		return b.toString().getBytes(UTF_8);
	}

	/**
	 * Returns whether a content type is JSON.
	 *
	 * @param contentType the content type
	 * @return {@code true} for {@code application/json} and {@code *+json}
	 */
	public static boolean isJson(String contentType) {
		if (contentType == null) {
			return false;
		}
		String ct = mime(contentType);
		return ct.equals("application/json") || ct.endsWith("+json");
	}

	private static boolean isText(String contentType) {
		return contentType != null && mime(contentType).startsWith("text/");
	}

	private static String mime(String contentType) {
		int semi = contentType.indexOf(';');
		return (semi < 0 ? contentType : contentType.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
	}

	private static String utf8(byte[] bytes) {
		try {
			return UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException e) {
			return null;
		}
	}

	private static void member(StringBuilder b, String name, String value) {
		if (value == null) {
			return;
		}
		comma(b);
		Json.quote(b, name);
		b.append(':');
		Json.quote(b, value);
	}

	private static void comma(StringBuilder b) {
		if (b.charAt(b.length() - 1) != '{') {
			b.append(',');
		}
	}

	private static void putIfSet(Map<String, Object> m, String key, String value) {
		if (value != null) {
			m.put(key, value);
		}
	}

	private static String str(Object o) {
		return o == null ? null : o.toString();
	}

	private static OffsetDateTime time(Object o) {
		if (o == null) {
			return null;
		}
		try {
			return OffsetDateTime.parse(o.toString());
		} catch (DateTimeParseException e) {
			throw new MessagingException("Invalid CloudEvents time '" + o + "'", e);
		}
	}

}
