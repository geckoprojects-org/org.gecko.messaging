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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.IntStream;

import org.gecko.messaging.api.MessagingException;
import org.gecko.messaging.core.ReconnectBackoff.Mode;
import org.junit.jupiter.api.Test;

class ReconnectBackoffTest {

	private static long[] intervals(ReconnectBackoff b, int n) {
		return IntStream.range(0, n).mapToLong(b::interval).toArray();
	}

	private static ReconnectBackoff of(Map<String, Object> props) {
		Map<String, Object> p = new HashMap<>(props);
		p.put("osgi.messaging.name", "x");
		return ReconnectBackoff.of(new MessagingConfiguration(p));
	}

	@Test
	void defaultsDoubleUpTo30SecondsAndGiveUpAfter100Attempts() {
		ReconnectBackoff b = of(Map.of());
		assertThat(intervals(b, 8)).containsExactly(1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000, 30_000);
		assertThat(b.maxAttempts()).isEqualTo(100);
		assertThat(b.exhausted(99)).isFalse();
		assertThat(b.exhausted(100)).isTrue();
	}

	@Test
	void exponentialThenStaysAtTheLastInterval() {
		// five growing attempts, then the last interval of those
		ReconnectBackoff b = of(Map.of("reconnectGrowthAttempts", 5, "reconnectMaxAttempts", 10, "reconnectMaxDelay", 3_600_000));
		assertThat(intervals(b, 10)).containsExactly(1_000, 2_000, 4_000, 8_000, 16_000, 16_000, 16_000, 16_000, 16_000, 16_000);
		assertThat(b.exhausted(10)).isTrue();
	}

	@Test
	void linearAddsTheFirstInterval() {
		ReconnectBackoff b = of(Map.of("reconnectBackoff", "linear", "reconnectDelay", 5_000, "reconnectMaxDelay", 20_000));
		assertThat(intervals(b, 6)).containsExactly(5_000, 10_000, 15_000, 20_000, 20_000, 20_000);
	}

	@Test
	void fixedStays() {
		ReconnectBackoff b = of(Map.of("reconnectBackoff", "FIXED", "reconnectDelay", 7_000));
		assertThat(intervals(b, 3)).containsExactly(7_000, 7_000, 7_000);
	}

	@Test
	void multiplierAndLongOutages() {
		ReconnectBackoff b = of(Map.of("reconnectMultiplier", "1.5", "reconnectMaxDelay", 900_000, "reconnectMaxAttempts", 0));
		assertThat(intervals(b, 3)).containsExactly(1_000, 1_500, 2_250);
		// no overflow however many attempts
		assertThat(b.interval(Integer.MAX_VALUE)).isEqualTo(900_000);
		assertThat(b.exhausted(Integer.MAX_VALUE)).as("0 never gives up").isFalse();
	}

	@Test
	void waitIsBetweenHalfAndAllOfTheInterval() {
		ReconnectBackoff b = new ReconnectBackoff(Mode.FIXED, 1_000, 1_000, 2, 0, 0);
		assertThat(b.delay(0, 0.0)).isEqualTo(500);
		assertThat(b.delay(0, 0.999_999)).isEqualTo(1_000);
	}

	@Test
	void zeroDelayIsNoBusyLoop() {
		assertThat(new ReconnectBackoff(Mode.EXPONENTIAL, 0, 0, 2, 0, 0).interval(0)).isEqualTo(ReconnectBackoff.MIN_DELAY);
	}

	@Test
	void invalidValuesFailTheInstance() {
		assertThatThrownBy(() -> of(Map.of("reconnectBackoff", "random"))).isInstanceOf(MessagingException.class)
				.hasMessageContaining("reconnectBackoff");
		assertThatThrownBy(() -> of(Map.of("reconnectMultiplier", "0.5"))).isInstanceOf(MessagingException.class);
		assertThatThrownBy(() -> of(Map.of("reconnectMultiplier", "NaN"))).isInstanceOf(MessagingException.class);
		assertThatThrownBy(() -> of(Map.of("reconnectMaxAttempts", -1))).isInstanceOf(MessagingException.class);
	}

}
