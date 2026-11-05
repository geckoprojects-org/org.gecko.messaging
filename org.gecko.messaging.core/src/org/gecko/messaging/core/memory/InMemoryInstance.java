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
package org.gecko.messaging.core.memory;

import static org.gecko.messaging.api.MessagingConstants.*;

import java.nio.ByteBuffer;
import java.util.List;

import org.gecko.messaging.core.AbstractMessagingInstance;
import org.gecko.messaging.core.MessagingConfiguration;
import org.gecko.messaging.core.SimpleMessageContext;
import org.gecko.messaging.core.SubscriptionHandle;
import org.osgi.util.promise.Promise;

/**
 * A messaging instance on an {@link InMemoryBroker}. The broker is named by the URI
 * {@code memory://<broker>}; without URI the broker {@code default} is used.
 */
public final class InMemoryInstance extends AbstractMessagingInstance {

	/** The protocol name. */
	public static final String PROTOCOL = "memory";

	/** All features — the in-memory broker supports the whole specification. */
	public static final List<String> FEATURES = List.of(FEATURE_PUBLISH_SUBSCRIBE, FEATURE_POINT_TO_POINT, FEATURE_WILDCARD,
			FEATURE_CHANNEL_EXTENSION, FEATURE_CONTENT_TYPE, FEATURE_HEADERS, FEATURE_QOS, FEATURE_TIME_TO_LIVE, FEATURE_RETAIN,
			FEATURE_AUTO_CREATE, FEATURE_DURABLE, FEATURE_ACKNOWLEDGE, FEATURE_REPLY_TO, FEATURE_REPLY_TO_MANY,
			FEATURE_REPLY_TO_SUBSCRIPTION, FEATURE_GENERATE_CORRELATION_ID, FEATURE_GENERATE_REPLY_CHANNEL);

	private final InMemoryBroker broker;
	private volatile InMemoryBroker.Connection connection;

	/**
	 * Creates the instance.
	 *
	 * @param config the configuration
	 */
	public InMemoryInstance(MessagingConfiguration config) {
		super(PROTOCOL, config, FEATURES);
		String uri = config.uri();
		String name = uri == null ? "default" : uri.replaceFirst("^memory://", "");
		this.broker = InMemoryBroker.get(name);
	}

	/** @return the broker of this instance */
	public InMemoryBroker broker() {
		return broker;
	}

	@Override
	protected boolean doConnect(long timeoutMillis) {
		connection = broker.connect(this);
		return false;
	}

	@Override
	protected void doDisconnect() {
		InMemoryBroker.Connection c = connection;
		connection = null;
		if (c != null) {
			c.close();
		}
	}

	void lost(Throwable cause) {
		connectionLost(cause);
	}

	@Override
	protected Promise<Void> doPublish(SimpleMessageContext context, ByteBuffer payload) {
		byte[] bytes = new byte[payload.remaining()];
		payload.get(bytes);
		connection().publish(context, bytes);
		return promises.resolved(null);
	}

	@Override
	protected void doSubscribe(SubscriptionHandle handle) {
		connection().subscribe(handle);
	}

	@Override
	protected void doUnsubscribe(SubscriptionHandle handle) {
		InMemoryBroker.Connection c = connection;
		if (c != null) {
			c.unsubscribe(handle);
		}
	}

	@Override
	protected boolean createsChannelsImplicitly() {
		// topics and queues exist as soon as they are used
		return true;
	}

	@Override
	protected String cloudEventHeaderPrefix() {
		return "ce-";
	}

	private InMemoryBroker.Connection connection() {
		InMemoryBroker.Connection c = connection;
		if (c == null) {
			throw new IllegalStateException("Not connected");
		}
		return c;
	}

}
