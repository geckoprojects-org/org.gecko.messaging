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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.runtime.ConnectionEvent;
import org.gecko.messaging.api.runtime.ConnectionListener;
import org.gecko.messaging.api.runtime.ConnectionState;
import org.gecko.messaging.api.runtime.MessageServiceRuntime;
import org.gecko.messaging.tck.spi.BrokerControl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.ServiceReference;

/**
 * Connection loss: the instance reconnects on its own, restores its subscriptions and reports the
 * gap. Needs a {@link BrokerControl} of the TCK run.
 */
public class ReconnectTest extends AbstractMessagingTest {

	private BrokerControl control;

	@BeforeEach
	void needsBrokerControl() {
		ServiceReference<BrokerControl> ref = bundleContext.getServiceReference(BrokerControl.class);
		assumeTrue(ref != null, "no BrokerControl in this TCK run");
		control = bundleContext.getService(ref);
	}

	@AfterEach
	void brokerBack() {
		if (control != null) {
			control.setAvailable(true);
			tck.awaitConnected();
		}
	}

	private List<ConnectionEvent> listen() {
		List<ConnectionEvent> events = new CopyOnWriteArrayList<>();
		Hashtable<String, Object> props = new Hashtable<>();
		props.put(MESSAGING_TARGET_PROPERTY, tck.filter());
		register(ConnectionListener.class, events::add, props);
		tck.awaitSize(events, 1);
		return events;
	}

	@Test
	void subscriptionsSurviveAConnectionLoss() throws Exception {
		String channel = tck.channel("reconnect");
		List<Message> received = collect(channel);
		List<ConnectionEvent> events = listen();
		publisher.publish(text(channel, "before")).getValue();
		tck.awaitSize(received, 1);
		control.dropConnections();
		tck.await("reconnected", () -> events.stream().anyMatch(ConnectionEvent::reconnect));
		assertThat(events).extracting(ConnectionEvent::state).containsSubsequence(ConnectionState.DISCONNECTED,
				ConnectionState.CONNECTED);
		publisher.publish(text(channel, "after")).getValue();
		tck.awaitSize(received, 2);
		assertThat(received).extracting(Tck::text).containsExactly("before", "after");
		assertThat(tck.service(MessageServiceRuntime.class).getRuntimeDTO().reconnects).isPositive();
	}

	@Test
	void publishFailsWhileDisconnectedAndWorksAfterwards() throws Exception {
		MessageServiceRuntime runtime = tck.service(MessageServiceRuntime.class);
		control.setAvailable(false);
		tck.await("disconnected", () -> runtime.getConnectionState() == ConnectionState.DISCONNECTED);
		String channel = tck.channel("offline");
		assertThat(publisher.publish(text(channel, "lost")).getFailure()).isNotNull();
		control.setAvailable(true);
		tck.awaitConnected();
		List<Message> received = collect(channel);
		publisher.publish(text(channel, "back")).getValue();
		tck.awaitSize(received, 1);
	}

}
