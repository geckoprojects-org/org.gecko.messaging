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

import java.util.Locale;

import org.gecko.messaging.api.MessagingException;

/**
 * The waits between connect attempts. The interval starts at {@code reconnectDelay} and grows per
 * attempt, {@code exponential} (times {@code reconnectMultiplier}), {@code linear} (plus
 * {@code reconnectDelay}) or not at all ({@code fixed}). It stops growing at
 * {@code reconnectMaxDelay} or after {@code reconnectGrowthAttempts} attempts, whichever comes
 * first, and stays at its last value from then on. After {@code reconnectMaxAttempts} failed
 * attempts in a row the instance gives up; {@code 0} never gives up.
 * <p>
 * The wait of an attempt is drawn between half and all of its interval, so that a fleet of
 * clients does not reconnect in lockstep after a broker restart.
 */
public final class ReconnectBackoff {

	/** The smallest interval, also for a configured 0. */
	static final long MIN_DELAY = 10;

	/** How the interval grows. */
	public enum Mode {
		/** Times the multiplier per attempt. */
		EXPONENTIAL,
		/** Plus the first interval per attempt. */
		LINEAR,
		/** Always the first interval. */
		FIXED
	}

	private final Mode mode;
	private final long delay;
	private final long maxDelay;
	private final double multiplier;
	private final int growthAttempts;
	private final int maxAttempts;

	/**
	 * Creates a backoff.
	 *
	 * @param mode how the interval grows
	 * @param delay the first interval in ms
	 * @param maxDelay the largest interval in ms
	 * @param multiplier the factor of {@link Mode#EXPONENTIAL}, at least 1
	 * @param growthAttempts the attempts after which the interval stays, {@code 0} for no limit
	 * @param maxAttempts the failed attempts in a row after which to give up, {@code 0} never
	 */
	public ReconnectBackoff(Mode mode, long delay, long maxDelay, double multiplier, int growthAttempts, int maxAttempts) {
		if (!(multiplier >= 1.0) || Double.isInfinite(multiplier)) {
			throw new MessagingException("'reconnectMultiplier' must be a number of at least 1: " + multiplier);
		}
		if (growthAttempts < 0 || maxAttempts < 0) {
			throw new MessagingException("'reconnectGrowthAttempts' and 'reconnectMaxAttempts' must not be negative");
		}
		this.mode = mode;
		this.delay = Math.max(MIN_DELAY, delay);
		this.maxDelay = Math.max(this.delay, maxDelay);
		this.multiplier = multiplier;
		this.growthAttempts = growthAttempts;
		this.maxAttempts = maxAttempts;
	}

	/**
	 * Reads the backoff from the configuration.
	 *
	 * @param config the configuration
	 * @return the backoff
	 * @throws MessagingException if a value is invalid
	 */
	public static ReconnectBackoff of(MessagingConfiguration config) {
		String mode = config.string("reconnectBackoff", "exponential").trim().toUpperCase(Locale.ROOT);
		Mode m;
		try {
			m = Mode.valueOf(mode);
		} catch (IllegalArgumentException e) {
			throw new MessagingException("Unknown reconnectBackoff '" + mode.toLowerCase(Locale.ROOT)
					+ "', expected exponential, linear or fixed");
		}
		String multiplier = config.string("reconnectMultiplier", "2");
		double f;
		try {
			f = Double.parseDouble(multiplier.trim());
		} catch (NumberFormatException e) {
			throw new MessagingException("'reconnectMultiplier' is no number: " + multiplier);
		}
		return new ReconnectBackoff(m, config.reconnectDelay(), config.reconnectMaxDelay(), f,
				(int) Math.min(Integer.MAX_VALUE, config.number("reconnectGrowthAttempts", 0)),
				(int) Math.min(Integer.MAX_VALUE, config.number("reconnectMaxAttempts", 100)));
	}

	/**
	 * Returns the interval of an attempt, before the jitter.
	 *
	 * @param attempt the failed attempts in a row before, from 0
	 * @return the interval in ms
	 */
	public long interval(int attempt) {
		int step = growthAttempts > 0 ? Math.min(attempt, growthAttempts - 1) : attempt;
		double d = switch (mode) {
			case EXPONENTIAL -> delay * Math.pow(multiplier, step);
			case LINEAR -> delay * (step + 1.0);
			case FIXED -> delay;
		};
		// a double beyond the range of long (or infinite) ends up at the largest interval
		return d >= maxDelay ? maxDelay : (long) d;
	}

	/**
	 * Returns the wait of an attempt: between half and all of its interval.
	 *
	 * @param attempt the failed attempts in a row before, from 0
	 * @param random a random number in {@code [0, 1)}
	 * @return the wait in ms
	 */
	public long delay(int attempt, double random) {
		long interval = interval(attempt);
		return interval / 2 + (long) (random * (interval / 2 + 1));
	}

	/**
	 * Returns whether to give up after a number of failed attempts in a row.
	 *
	 * @param failed the failed attempts in a row
	 * @return {@code true} to give up
	 */
	public boolean exhausted(int failed) {
		return maxAttempts > 0 && failed >= maxAttempts;
	}

	/** @return the failed attempts in a row after which to give up, {@code 0} never */
	public int maxAttempts() {
		return maxAttempts;
	}

}
