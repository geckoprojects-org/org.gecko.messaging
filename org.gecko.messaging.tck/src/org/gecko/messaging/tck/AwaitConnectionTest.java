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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessagePublisher;
import org.gecko.messaging.api.MessageSubscription;
import org.gecko.messaging.api.runtime.ConnectionState;
import org.gecko.messaging.tck.spi.BrokerControl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.ServiceReference;
import org.osgi.util.promise.Promise;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamProvider;

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
	private Sibling sibling;

	@BeforeEach
	void needsBrokerControl() {
		ServiceReference<BrokerControl> ref = bundleContext.getServiceReference(BrokerControl.class);
		assumeTrue(ref != null, "no BrokerControl in this TCK run");
		control = bundleContext.getService(ref);
	}

	@AfterEach
	void removeSibling() throws Exception {
		if (control != null) {
			control.setAvailable(true);
		}
		if (sibling != null) {
			sibling.close();
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
	private void sibling(Map<String, Object> extra) throws Exception {
		sibling = new Sibling(tck, "await", extra);
		sibling.awaitConnected();
	}

	private void disconnect() {
		control.setAvailable(false);
		tck.await("sibling disconnected", () -> sibling.state() == ConnectionState.DISCONNECTED);
	}

	@Test
	void aPublishWaitsForTheConnectionWhenConfigured() throws Exception {
		sibling(Map.of("publishAwaitConnection", Long.valueOf(4 * tck.timeout())));
		MessagePublisher siblingPublisher = sibling.service(MessagePublisher.class);
		String channel = tck.channel("await");
		List<Message> received = collect(sibling.service(MessageSubscription.class).subscribe(channel));
		tck.settle();
		disconnect();
		List<Promise<Void>> sent = Stream.of("a", "b", "c").map(t -> siblingPublisher.publish(text(channel, t))).toList();
		assertThat(sent).as("waiting, not failed").noneMatch(Promise::isDone);
		assertThat(sibling.runtime().getRuntimeDTO().awaitingPublishes).isEqualTo(3);
		control.setAvailable(true);
		for (Promise<Void> p : sent) {
			assertThat(p.getFailure()).as("handed over after the reconnect").isNull();
		}
		tck.awaitSize(received, 3);
		assertThat(received).extracting(Tck::text).containsExactly("a", "b", "c");
	}

	@Test
	void aStreamPublishWaitsForTheConnectionWhenConfigured() throws Exception {
		sibling(Map.of("streamPublishAwaitConnection", Boolean.TRUE));
		MessagePublisher siblingPublisher = sibling.service(MessagePublisher.class);
		String channel = tck.channel("await-stream");
		List<Message> received = collect(sibling.service(MessageSubscription.class).subscribe(channel));
		tck.settle();
		disconnect();
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
		assertThat(sibling.runtime().getRuntimeDTO().streamPublishFailures).isZero();
	}

}
