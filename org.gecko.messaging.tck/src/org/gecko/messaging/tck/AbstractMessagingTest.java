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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.MessageContextBuilder;
import org.gecko.messaging.api.MessagePublisher;
import org.gecko.messaging.api.MessageSubscription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.test.common.annotation.InjectBundleContext;
import org.osgi.test.junit5.context.BundleContextExtension;
import org.osgi.util.pushstream.PushStream;

/**
 * Base of the TCK tests: finds the instance under test, and closes the streams and unregisters
 * the services of a test afterwards.
 */
@ExtendWith(BundleContextExtension.class)
public abstract class AbstractMessagingTest {

	@InjectBundleContext
	BundleContext bundleContext;

	protected Tck tck;
	protected MessagePublisher publisher;
	protected MessageSubscription subscription;
	private final List<PushStream<?>> streams = new ArrayList<>();
	private final List<ServiceRegistration<?>> registrations = new ArrayList<>();

	@BeforeEach
	void setUpTck() {
		tck = new Tck(bundleContext);
		tck.awaitConnected();
		publisher = tck.service(MessagePublisher.class);
		subscription = tck.service(MessageSubscription.class);
	}

	@AfterEach
	void tearDownTck() {
		streams.forEach(PushStream::close);
		streams.clear();
		for (ServiceRegistration<?> r : registrations) {
			try {
				r.unregister();
			} catch (IllegalStateException e) {
				// gone already
			}
		}
		registrations.clear();
	}

	/** @return a fresh builder of the instance under test */
	protected MessageContextBuilder builder() {
		return tck.builder();
	}

	/**
	 * Subscribes and collects every message; the stream is closed after the test.
	 *
	 * @param stream the stream
	 * @return the live list of received messages
	 */
	protected List<Message> collect(PushStream<Message> stream) {
		streams.add(stream);
		List<Message> received = new CopyOnWriteArrayList<>();
		stream.forEach(received::add);
		return received;
	}

	/**
	 * Subscribes to a channel and collects.
	 *
	 * @param channel the channel
	 * @return the live list
	 */
	protected List<Message> collect(String channel) {
		return collect(subscription.subscribe(channel));
	}

	/**
	 * Subscribes with a context and collects.
	 *
	 * @param context the context
	 * @return the live list
	 */
	protected List<Message> collect(MessageContext context) {
		return collect(subscription.subscribe(context));
	}

	/**
	 * Keeps a stream to close after the test.
	 *
	 * @param <T> the element type
	 * @param stream the stream
	 * @return the stream
	 */
	protected <T> PushStream<T> track(PushStream<T> stream) {
		streams.add(stream);
		return stream;
	}

	/**
	 * Registers a service for the duration of the test.
	 *
	 * @param <S> the service type
	 * @param type the service interface
	 * @param service the service
	 * @param properties the properties
	 */
	protected <S> void register(Class<S> type, S service, java.util.Dictionary<String, ?> properties) {
		registrations.add(bundleContext.registerService(type, service, properties));
	}

	/**
	 * Builds a text message to a channel.
	 *
	 * @param channel the channel
	 * @param text the text
	 * @return the message
	 */
	protected Message text(String channel, String text) {
		return builder().channel(channel).content(Tck.bytes(text)).buildMessage();
	}

}
