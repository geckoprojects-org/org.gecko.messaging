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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.gecko.messaging.api.MessagingConstants.MESSAGING_FEATURE_PROPERTY;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContextBuilder;
import org.gecko.messaging.api.runtime.ConnectionState;
import org.gecko.messaging.api.runtime.MessageServiceRuntime;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceObjects;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;

/**
 * Access to the messaging instance under test and the knobs of a TCK run, read from framework
 * properties:
 * <ul>
 * <li>{@code org.gecko.messaging.tck.filter} — selects the instance, default
 * {@code (osgi.messaging.name=tck)}</li>
 * <li>{@code org.gecko.messaging.tck.timeout} — ms to wait for anything, default 10000</li>
 * <li>{@code org.gecko.messaging.tck.separator} — separator of channel name segments, default
 * {@code /}</li>
 * <li>{@code org.gecko.messaging.tck.wildcard.single}, {@code ...wildcard.multi} — the wildcards
 * of the protocol, default {@code +} and {@code #}</li>
 * </ul>
 */
public final class Tck {

	/** Framework property prefix. */
	public static final String PREFIX = "org.gecko.messaging.tck.";

	/** Services that did not show up: later tests fail at once instead of waiting again. */
	private static final java.util.Map<Class<?>, String> MISSING = new java.util.concurrent.ConcurrentHashMap<>();

	private final BundleContext context;
	private final String filter;
	private final long timeout;
	private final String separator;
	private final String singleWildcard;
	private final String multiWildcard;
	private final String run = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

	/**
	 * Reads the run configuration.
	 *
	 * @param context the bundle context of the TCK
	 */
	public Tck(BundleContext context) {
		this.context = context;
		this.filter = property("filter", "(osgi.messaging.name=tck)");
		this.timeout = Long.parseLong(property("timeout", "10000"));
		this.separator = property("separator", "/");
		this.singleWildcard = property("wildcard.single", "+");
		this.multiWildcard = property("wildcard.multi", "#");
	}

	private String property(String key, String defaultValue) {
		String v = context.getProperty(PREFIX + key);
		return v == null || v.isBlank() ? defaultValue : v;
	}

	/** @return the bundle context */
	public BundleContext context() {
		return context;
	}

	/** @return the wait timeout in ms */
	public long timeout() {
		return timeout;
	}

	/** @return the instance filter */
	public String filter() {
		return filter;
	}

	/** @return the single-level wildcard */
	public String singleWildcard() {
		return singleWildcard;
	}

	/** @return the multi-level wildcard */
	public String multiWildcard() {
		return multiWildcard;
	}

	/**
	 * Returns a channel name unique to this run.
	 *
	 * @param segments the segments after the run prefix
	 * @return the channel name
	 */
	public String channel(String... segments) {
		StringBuilder b = new StringBuilder("tck").append(separator).append(run);
		for (String s : segments) {
			b.append(separator).append(s);
		}
		return b.toString();
	}

	/**
	 * Returns a service of the instance under test, waiting for it.
	 *
	 * @param <T> the service type
	 * @param type the service type
	 * @return the service
	 */
	public <T> T service(Class<T> type) {
		String missing = MISSING.get(type);
		if (missing != null) {
			throw new AssertionError(missing + " (failed before, not waiting again)");
		}
		ServiceTracker<T, T> tracker = new ServiceTracker<>(context, filter(type), null);
		tracker.open();
		try {
			T service = tracker.waitForService(timeout);
			if (service == null) {
				String message = "No " + type.getSimpleName() + " matching " + filter + " within " + timeout + " ms";
				MISSING.put(type, message);
				throw new AssertionError(message);
			}
			return service;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		} finally {
			// the instance keeps the service; the tracker only found it
			tracker.close();
		}
	}

	/**
	 * Returns the reference of a service of the instance under test, or {@code null}.
	 *
	 * @param <T> the service type
	 * @param type the service type
	 * @return the reference, or {@code null}
	 */
	public <T> ServiceReference<T> reference(Class<T> type) {
		try {
			Collection<ServiceReference<T>> refs = context.getServiceReferences(type, filter);
			return refs.isEmpty() ? null : refs.iterator().next();
		} catch (InvalidSyntaxException e) {
			throw new AssertionError(e);
		}
	}

	private Filter filter(Class<?> type) {
		try {
			return FrameworkUtil.createFilter("(&(objectClass=" + type.getName() + ")" + filter + ")");
		} catch (InvalidSyntaxException e) {
			throw new AssertionError("Invalid " + PREFIX + "filter " + filter, e);
		}
	}

	/**
	 * Returns a fresh builder of the instance under test (the builder service is prototype scoped).
	 *
	 * @return a builder
	 */
	public MessageContextBuilder builder() {
		service(MessageContextBuilder.class);
		ServiceObjects<MessageContextBuilder> objects = context.getServiceObjects(reference(MessageContextBuilder.class));
		return objects.getService();
	}

	/** @return the features the instance announces */
	public Set<String> features() {
		service(MessageServiceRuntime.class);
		Object f = reference(MessageServiceRuntime.class).getProperty(MESSAGING_FEATURE_PROPERTY);
		Set<String> result = new TreeSet<>();
		if (f instanceof String[] a) {
			result.addAll(Arrays.asList(a));
		} else if (f instanceof Collection<?> c) {
			c.forEach(x -> result.add(String.valueOf(x)));
		} else if (f != null) {
			result.add(f.toString());
		}
		return result;
	}

	/**
	 * Returns whether the instance announces the feature.
	 *
	 * @param feature the feature
	 * @return {@code true} if announced
	 */
	public boolean has(String feature) {
		return features().contains(feature);
	}

	/** Waits until the instance under test is connected. */
	public void awaitConnected() {
		MessageServiceRuntime runtime = service(MessageServiceRuntime.class);
		await("instance connected", () -> runtime.getConnectionState() == ConnectionState.CONNECTED);
	}

	/**
	 * Waits until a condition holds.
	 *
	 * @param what what is awaited, for the failure message
	 * @param condition the condition
	 */
	public void await(String what, BooleanSupplier condition) {
		long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > end) {
				throw new AssertionError("Timed out after " + timeout + " ms waiting for: " + what);
			}
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
		}
	}

	/**
	 * Waits until the list holds at least the given number of elements.
	 *
	 * @param list the list
	 * @param size the size
	 */
	public void awaitSize(List<?> list, int size) {
		await(size + " messages (have " + list.size() + ")", () -> list.size() >= size);
	}

	/**
	 * Gives messages that must <em>not</em> arrive a chance to arrive.
	 */
	public void settle() {
		try {
			Thread.sleep(Math.min(500, timeout / 10));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Returns the payload as UTF-8 text.
	 *
	 * @param message the message
	 * @return the text
	 */
	public static String text(Message message) {
		ByteBuffer b = message.payload();
		byte[] bytes = new byte[b.remaining()];
		b.get(bytes);
		return new String(bytes, UTF_8);
	}

	/**
	 * Returns UTF-8 bytes of a text as buffer.
	 *
	 * @param text the text
	 * @return the buffer
	 */
	public static ByteBuffer bytes(String text) {
		return ByteBuffer.wrap(text.getBytes(UTF_8));
	}

}
