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
import static org.gecko.messaging.api.MessagingConstants.MESSAGING_TARGET_PROPERTY;

import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.runtime.ConnectionEvent;
import org.gecko.messaging.api.runtime.ConnectionListener;
import org.gecko.messaging.api.runtime.ConnectionState;
import org.gecko.messaging.api.runtime.MessageServiceRuntime;
import org.junit.jupiter.api.Test;
import org.osgi.util.pushstream.PushStream;

/**
 * Introspection of the instance and its connection (MSG060).
 */
public class RuntimeTest extends AbstractMessagingTest {

	@Test
	void subscriptionsAreListed() throws Exception {
		MessageServiceRuntime runtime = tck.service(MessageServiceRuntime.class);
		String channel = tck.channel("dto");
		PushStream<Message> stream = track(subscription.subscribe(channel));
		List<Message> received = new CopyOnWriteArrayList<>();
		stream.forEach(received::add);
		tck.await("subscription listed", () -> Arrays.stream(runtime.getRuntimeDTO().subscriptions)
				.anyMatch(s -> channel.equals(s.channel.name) && s.channel.connected));
		publisher.publish(text(channel, "count me")).getValue();
		tck.awaitSize(received, 1);
		tck.await("message counted", () -> Arrays.stream(runtime.getRuntimeDTO().subscriptions)
				.anyMatch(s -> channel.equals(s.channel.name) && s.receivedMessages == 1));
		stream.close();
		tck.await("subscription gone", () -> Arrays.stream(runtime.getRuntimeDTO().subscriptions)
				.noneMatch(s -> channel.equals(s.channel.name)));
	}

	@Test
	void listenerIsToldTheCurrentState() {
		List<ConnectionEvent> events = new CopyOnWriteArrayList<>();
		Hashtable<String, Object> props = new Hashtable<>();
		props.put(MESSAGING_TARGET_PROPERTY, tck.filter());
		register(ConnectionListener.class, events::add, props);
		tck.awaitSize(events, 1);
		ConnectionEvent e = events.get(0);
		assertThat(e.state()).isEqualTo(ConnectionState.CONNECTED);
		assertThat(e.previousState()).isNull();
		assertThat(e.instanceName()).isNotNull();
	}

	@Test
	void listenerWithForeignTargetHearsNothing() {
		List<ConnectionEvent> events = new CopyOnWriteArrayList<>();
		Hashtable<String, Object> props = new Hashtable<>();
		props.put(MESSAGING_TARGET_PROPERTY, "(osgi.messaging.name=no-such-instance-" + System.nanoTime() + ")");
		register(ConnectionListener.class, events::add, props);
		tck.settle();
		assertThat(events).isEmpty();
	}

}
