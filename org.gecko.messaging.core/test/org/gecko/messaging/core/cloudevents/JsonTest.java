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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The limits of the envelope parser against hostile input. */
class JsonTest {

	@Test
	void deepNestingIsRejectedNotAStackOverflow() {
		String deep = "{\"data\":" + "[".repeat(20_000) + "]".repeat(20_000) + "}";
		assertThatThrownBy(() -> Json.parseObject(deep)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Nested deeper");
		assertThat(Json.isValue("[".repeat(20_000) + "]".repeat(20_000))).isFalse();
	}

	@Test
	void nestingUpToTheLimitIsAccepted() {
		// the envelope object is the first level
		int n = Json.MAX_DEPTH - 1;
		assertThat(Json.parseObject("{\"data\":" + "[".repeat(n) + "]".repeat(n) + "}").get("data")).isInstanceOf(Json.Raw.class);
		assertThatThrownBy(() -> Json.parseObject("{\"data\":" + "[".repeat(n + 1) + "]".repeat(n + 1) + "}"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void longNumbersAreRejectedWithoutEchoingThem() {
		String digits = "9".repeat(100_000);
		assertThatThrownBy(() -> Json.parseObject("{\"n\":" + digits + "}")).isInstanceOf(IllegalArgumentException.class)
				.hasMessageNotContaining("999");
	}

	@Test
	void duplicateMembersAreRejected() {
		assertThatThrownBy(() -> Json.parseObject("{\"type\":\"a\",\"type\":\"b\"}")).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Duplicate");
	}

	@Test
	void unicodeEscapesNeedFourHexDigits() {
		assertThat(Json.parseObject("{\"s\":\"\\u0041\"}").get("s")).isEqualTo("A");
		assertThatThrownBy(() -> Json.parseObject("{\"s\":\"\\u+041\"}")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Json.parseObject("{\"s\":\"\\u00\"}")).isInstanceOf(IllegalArgumentException.class);
	}

}
