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

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The little JSON the CloudEvents envelope needs: one flat object whose values are scalars, and
 * whose nested values (the {@code data} member) are kept as raw JSON text. No dependency, no
 * reflection — the envelope must stay cheap on small devices.
 */
final class Json {

	/** A JSON value kept as its source text. */
	record Raw(String text) {
	}

	private final String s;
	private int pos;

	private Json(String s) {
		this.s = s;
	}

	/**
	 * Parses a JSON object.
	 *
	 * @param text the JSON text
	 * @return the members: {@code String}, {@code Long}, {@code BigDecimal}, {@code Boolean},
	 *         {@code null} or {@link Raw} for arrays and objects
	 * @throws IllegalArgumentException if the text is no JSON object
	 */
	static Map<String, Object> parseObject(String text) {
		Json p = new Json(text);
		p.ws();
		Map<String, Object> result = p.object();
		p.ws();
		if (p.pos != p.s.length()) {
			throw p.error("Trailing content");
		}
		return result;
	}

	/**
	 * Returns whether the text is one valid JSON value.
	 *
	 * @param text the text
	 * @return {@code true} if valid
	 */
	static boolean isValue(String text) {
		try {
			Json p = new Json(text);
			p.ws();
			p.skipValue();
			p.ws();
			return p.pos == p.s.length();
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private Map<String, Object> object() {
		expect('{');
		Map<String, Object> members = new LinkedHashMap<>();
		ws();
		if (peek() == '}') {
			pos++;
			return members;
		}
		while (true) {
			ws();
			String key = string();
			ws();
			expect(':');
			ws();
			members.put(key, value());
			ws();
			char c = next();
			if (c == '}') {
				return members;
			}
			if (c != ',') {
				throw error("Expected , or }");
			}
		}
	}

	private Object value() {
		char c = peek();
		switch (c) {
			case '"':
				return string();
			case '{':
			case '[': {
				int start = pos;
				skipValue();
				return new Raw(s.substring(start, pos));
			}
			case 't':
				literal("true");
				return Boolean.TRUE;
			case 'f':
				literal("false");
				return Boolean.FALSE;
			case 'n':
				literal("null");
				return null;
			default:
				return number();
		}
	}

	private void skipValue() {
		char c = peek();
		if (c == '{' || c == '[') {
			char close = c == '{' ? '}' : ']';
			pos++;
			ws();
			if (peek() == close) {
				pos++;
				return;
			}
			while (true) {
				ws();
				if (c == '{') {
					string();
					ws();
					expect(':');
					ws();
				}
				skipValue();
				ws();
				char n = next();
				if (n == close) {
					return;
				}
				if (n != ',') {
					throw error("Expected , or " + close);
				}
			}
		}
		value();
	}

	private Object number() {
		int start = pos;
		if (peek() == '-') {
			pos++;
		}
		while (pos < s.length() && "0123456789.eE+-".indexOf(s.charAt(pos)) >= 0) {
			pos++;
		}
		String n = s.substring(start, pos);
		if (n.isEmpty() || n.equals("-")) {
			throw error("Expected a value");
		}
		try {
			BigDecimal d = new BigDecimal(n);
			if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0) {
				return d.longValueExact();
			}
			return d;
		} catch (ArithmeticException | NumberFormatException e) {
			throw error("Invalid number " + n);
		}
	}

	private String string() {
		expect('"');
		StringBuilder b = new StringBuilder();
		while (true) {
			char c = next();
			if (c == '"') {
				return b.toString();
			}
			if (c == '\\') {
				char e = next();
				switch (e) {
					case '"', '\\', '/' -> b.append(e);
					case 'b' -> b.append('\b');
					case 'f' -> b.append('\f');
					case 'n' -> b.append('\n');
					case 'r' -> b.append('\r');
					case 't' -> b.append('\t');
					case 'u' -> {
						if (pos + 4 > s.length()) {
							throw error("Bad unicode escape");
						}
						b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
						pos += 4;
					}
					default -> throw error("Bad escape \\" + e);
				}
			} else if (c < 0x20) {
				throw error("Control character in string");
			} else {
				b.append(c);
			}
		}
	}

	private void literal(String word) {
		if (!s.startsWith(word, pos)) {
			throw error("Expected " + word);
		}
		pos += word.length();
	}

	private void ws() {
		while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
			pos++;
		}
	}

	private char peek() {
		if (pos >= s.length()) {
			throw error("Unexpected end");
		}
		return s.charAt(pos);
	}

	private char next() {
		char c = peek();
		pos++;
		return c;
	}

	private void expect(char c) {
		if (next() != c) {
			throw error("Expected " + c);
		}
	}

	private IllegalArgumentException error(String message) {
		return new IllegalArgumentException(message + " at " + pos);
	}

	/**
	 * Appends a quoted JSON string.
	 *
	 * @param b the target
	 * @param value the string
	 */
	static void quote(StringBuilder b, String value) {
		b.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> b.append("\\\"");
				case '\\' -> b.append("\\\\");
				case '\n' -> b.append("\\n");
				case '\r' -> b.append("\\r");
				case '\t' -> b.append("\\t");
				case '\b' -> b.append("\\b");
				case '\f' -> b.append("\\f");
				default -> {
					if (c < 0x20) {
						b.append(String.format("\\u%04x", (int) c));
					} else {
						b.append(c);
					}
				}
			}
		}
		b.append('"');
	}

}
