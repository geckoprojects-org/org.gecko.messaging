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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessagePublisher;
import org.gecko.messaging.api.MessageSubscription;
import org.gecko.messaging.api.runtime.ConnectionState;
import org.gecko.messaging.api.runtime.MessageServiceRuntime;
import org.gecko.messaging.tck.spi.BrokerControl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.util.promise.Promise;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamProvider;
import org.osgi.util.tracker.ServiceTracker;

/**
 * Publishing that waits for the connection, off by default (§6, §6.1): a sibling of the instance
 * under test, configured like it plus {@code publishAwaitConnection} or
 * {@code streamPublishAwaitConnection}, publishes while the broker is unreachable; the messages
 * arrive once it is back. The sibling subscribes itself: its subscriptions are restored before
 * what waited goes out. Needs a {@link BrokerControl} and a configuration of the instance under
 * test in Configuration Admin.
 */
public class AwaitConnectionTest extends AbstractMessagingTest {

	private BrokerControl control;
	private ConfigurationAdmin configurationAdmin;
	private Configuration sibling;
	private String siblingName;

	@BeforeEach
	void needsBrokerControlAndConfigurationAdmin() {
		ServiceReference<BrokerControl> ref = bundleContext.getServiceReference(BrokerControl.class);
		assumeTrue(ref != null, "no BrokerControl in this TCK run");
		control = bundleContext.getService(ref);
		ServiceReference<ConfigurationAdmin> cm = bundleContext.getServiceReference(ConfigurationAdmin.class);
		assumeTrue(cm != null, "no Configuration Admin in this TCK run");
		configurationAdmin = bundleContext.getService(cm);
	}

	@AfterEach
	void removeSibling() throws Exception {
		if (control != null) {
			control.setAvailable(true);
		}
		if (sibling != null) {
			sibling.delete();
			sibling = null;
		}
		if (control != null) {
			tck.awaitConnected();
		}
	}

	/**
	 * Creates a sibling of the instance under test with additional configuration and waits until
	 * it is connected.
	 */
	private ServiceTracker<Object, Object> sibling(Map<String, Object> extra) throws Exception {
		String name = tck.service(MessageServiceRuntime.class).getRuntimeDTO().name;
		Configuration[] own = configurationAdmin.listConfigurations("(" + MESSAGING_NAME_PROPERTY + "=" + name + ")");
		assumeTrue(own != null && own.length == 1 && own[0].getFactoryPid() != null,
				"the instance under test has no factory configuration");
		Dictionary<String, Object> props = new Hashtable<>();
		for (Enumeration<String> keys = own[0].getProperties().keys(); keys.hasMoreElements();) {
			String key = keys.nextElement();
			if (!key.equals(Constants.SERVICE_PID) && !key.startsWith("service.factoryPid")
					&& !key.equals(ConfigurationAdmin.SERVICE_BUNDLELOCATION)) {
				props.put(key, own[0].getProperties().get(key));
			}
		}
		siblingName = name + "-await-" + UUID.randomUUID().toString().substring(0, 8);
		props.put(MESSAGING_NAME_PROPERTY, siblingName);
		extra.forEach(props::put);
		sibling = configurationAdmin.getFactoryConfiguration(own[0].getFactoryPid(), siblingName, "?");
		sibling.update(props);
		Filter filter = FrameworkUtil.createFilter("(&(" + Constants.OBJECTCLASS + "=" + MessageServiceRuntime.class.getName()
				+ ")(" + MESSAGING_NAME_PROPERTY + "=" + siblingName + "))");
		ServiceTracker<Object, Object> runtime = new ServiceTracker<>(bundleContext, filter, null);
		runtime.open();
		assertThat(runtime.waitForService(tck.timeout())).as("the sibling instance").isNotNull();
		tck.await("sibling connected", () -> state(runtime) == ConnectionState.CONNECTED);
		return runtime;
	}

	private static ConnectionState state(ServiceTracker<Object, Object> runtime) {
		Object r = runtime.getService();
		return r == null ? null : ((MessageServiceRuntime) r).getConnectionState();
	}

	private <T> T siblingService(Class<T> type) throws Exception {
		Filter filter = FrameworkUtil.createFilter("(&(" + Constants.OBJECTCLASS + "=" + type.getName() + ")("
				+ MESSAGING_NAME_PROPERTY + "=" + siblingName + "))");
		ServiceTracker<Object, Object> t = new ServiceTracker<>(bundleContext, filter, null);
		t.open();
		Object s = t.waitForService(tck.timeout());
		assertThat(s).as(type.getSimpleName() + " of the sibling").isNotNull();
		return type.cast(s);
	}

	private void disconnect(ServiceTracker<Object, Object> runtime) {
		control.setAvailable(false);
		tck.await("sibling disconnected", () -> state(runtime) == ConnectionState.DISCONNECTED);
	}

	@Test
	void aPublishWaitsForTheConnectionWhenConfigured() throws Exception {
		ServiceTracker<Object, Object> runtime = sibling(Map.of("publishAwaitConnection", Long.valueOf(4 * tck.timeout())));
		MessagePublisher siblingPublisher = siblingService(MessagePublisher.class);
		String channel = tck.channel("await");
		List<Message> received = collect(siblingService(MessageSubscription.class).subscribe(channel));
		tck.settle();
		disconnect(runtime);
		List<Promise<Void>> sent = Stream.of("a", "b", "c").map(t -> siblingPublisher.publish(text(channel, t))).toList();
		assertThat(sent).as("waiting, not failed").noneMatch(Promise::isDone);
		assertThat(((MessageServiceRuntime) runtime.getService()).getRuntimeDTO().awaitingPublishes).isEqualTo(3);
		control.setAvailable(true);
		for (Promise<Void> p : sent) {
			assertThat(p.getFailure()).as("handed over after the reconnect").isNull();
		}
		tck.awaitSize(received, 3);
		assertThat(received).extracting(Tck::text).containsExactly("a", "b", "c");
		runtime.close();
	}

	@Test
	void aStreamPublishWaitsForTheConnectionWhenConfigured() throws Exception {
		ServiceTracker<Object, Object> runtime = sibling(Map.of("streamPublishAwaitConnection", Boolean.TRUE));
		MessagePublisher siblingPublisher = siblingService(MessagePublisher.class);
		String channel = tck.channel("await-stream");
		List<Message> received = collect(siblingService(MessageSubscription.class).subscribe(channel));
		tck.settle();
		disconnect(runtime);
		// a source with a thread of its own: the thread delivering the stream is the one that waits
		PushStream<Message> stream = new PushStreamProvider()
				.streamOf(IntStream.range(0, 5).mapToObj(n -> text(channel, "m" + n))).buffer();
		Promise<Long> p = siblingPublisher.publish(stream);
		TimeUnit.MILLISECONDS.sleep(300);
		assertThat(p.isDone()).as("waiting, not failed").isFalse();
		control.setAvailable(true);
		assertThat(p.getValue()).isEqualTo(5);
		tck.awaitSize(received, 5);
		assertThat(received).extracting(Tck::text).containsExactly("m0", "m1", "m2", "m3", "m4");
		assertThat(((MessageServiceRuntime) runtime.getService()).getRuntimeDTO().streamPublishFailures).isZero();
		runtime.close();
	}

}
