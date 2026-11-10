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
import static org.gecko.messaging.api.MessagingConstants.MESSAGING_NAME_PROPERTY;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Map;
import java.util.UUID;

import org.gecko.messaging.api.runtime.ConnectionState;
import org.gecko.messaging.api.runtime.MessageServiceRuntime;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.util.tracker.ServiceTracker;

/**
 * A sibling of the instance under test: an instance of the same factory, configured like it plus
 * what a test adds or replaces, under a name of its own. Needs the configuration of the instance
 * under test in Configuration Admin; a test using it is skipped otherwise. Closing deletes the
 * configuration, and with it the instance.
 */
final class Sibling implements AutoCloseable {

	private final Tck tck;
	private final BundleContext context;
	private final String name;
	private final Configuration configuration;
	private final ServiceTracker<MessageServiceRuntime, MessageServiceRuntime> runtime;

	/**
	 * Creates the sibling and waits for its services; it need not be connected yet.
	 *
	 * @param tck the run
	 * @param label a part of the name
	 * @param extra the configuration that differs from the instance under test
	 * @throws Exception if the configuration cannot be created
	 */
	Sibling(Tck tck, String label, Map<String, Object> extra) throws Exception {
		this.tck = tck;
		this.context = tck.context();
		ServiceReference<ConfigurationAdmin> cm = context.getServiceReference(ConfigurationAdmin.class);
		assumeTrue(cm != null, "no Configuration Admin in this TCK run");
		ConfigurationAdmin configurationAdmin = context.getService(cm);
		String own = tck.service(MessageServiceRuntime.class).getRuntimeDTO().name;
		Configuration[] found = configurationAdmin.listConfigurations("(" + MESSAGING_NAME_PROPERTY + "=" + own + ")");
		assumeTrue(found != null && found.length == 1 && found[0].getFactoryPid() != null,
				"the instance under test has no factory configuration");
		Dictionary<String, Object> props = new Hashtable<>();
		for (Enumeration<String> keys = found[0].getProperties().keys(); keys.hasMoreElements();) {
			String key = keys.nextElement();
			if (!key.equals(Constants.SERVICE_PID) && !key.startsWith("service.factoryPid")
					&& !key.equals(ConfigurationAdmin.SERVICE_BUNDLELOCATION)) {
				props.put(key, found[0].getProperties().get(key));
			}
		}
		name = own + "-" + label + "-" + UUID.randomUUID().toString().substring(0, 8);
		props.put(MESSAGING_NAME_PROPERTY, name);
		extra.forEach(props::put);
		configuration = configurationAdmin.getFactoryConfiguration(found[0].getFactoryPid(), name, "?");
		configuration.update(props);
		runtime = new ServiceTracker<>(context, MessageServiceRuntime.class.getName(), null) {
			@Override
			public MessageServiceRuntime addingService(ServiceReference<MessageServiceRuntime> reference) {
				return name.equals(reference.getProperty(MESSAGING_NAME_PROPERTY)) ? super.addingService(reference) : null;
			}
		};
		runtime.open();
		try {
			assertThat(runtime.waitForService(tck.timeout())).as("the sibling instance " + name).isNotNull();
		} catch (Throwable t) {
			close();
			throw t;
		}
	}

	/** @return the name of the sibling */
	String name() {
		return name;
	}

	/** @return the runtime of the sibling */
	MessageServiceRuntime runtime() {
		MessageServiceRuntime r = runtime.getService();
		assertThat(r).as("the runtime of the sibling " + name).isNotNull();
		return r;
	}

	/** @return the reference of the runtime of the sibling */
	ServiceReference<MessageServiceRuntime> runtimeReference() {
		return runtime.getServiceReference();
	}

	/** @return the connection state, {@code null} once the sibling is gone */
	ConnectionState state() {
		MessageServiceRuntime r = runtime.getService();
		return r == null ? null : r.getConnectionState();
	}

	/** Waits until the sibling is connected. */
	void awaitConnected() {
		tck.await("sibling connected", () -> state() == ConnectionState.CONNECTED);
	}

	/**
	 * Returns a service of the sibling, waiting for it.
	 *
	 * @param <T> the service type
	 * @param type the service type
	 * @return the service
	 * @throws Exception if waiting fails
	 */
	<T> T service(Class<T> type) throws Exception {
		Filter filter = FrameworkUtil.createFilter(
				"(&(" + Constants.OBJECTCLASS + "=" + type.getName() + ")(" + MESSAGING_NAME_PROPERTY + "=" + name + "))");
		ServiceTracker<Object, Object> t = new ServiceTracker<>(context, filter, null);
		t.open();
		try {
			Object s = t.waitForService(tck.timeout());
			assertThat(s).as(type.getSimpleName() + " of the sibling").isNotNull();
			return type.cast(s);
		} finally {
			// the instance keeps the service; the tracker only found it
			t.close();
		}
	}

	@Override
	public void close() throws Exception {
		runtime.close();
		configuration.delete();
	}

}
