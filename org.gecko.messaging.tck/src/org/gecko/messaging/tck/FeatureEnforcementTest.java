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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.gecko.messaging.api.MessagingConstants.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.gecko.messaging.api.FeatureNotSupportedException;
import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.acknowledge.AcknowledgeMessageContextBuilder;
import org.gecko.messaging.api.cloudevents.CloudEventMessageContextBuilder;
import org.junit.jupiter.api.Test;
import org.osgi.util.promise.Promise;

/**
 * Using a feature the instance does not announce fails (MSG090, MSG100).
 */
public class FeatureEnforcementTest extends AbstractMessagingTest {

	private void publishFails(MessageContext ctx, String feature) throws Exception {
		Promise<Void> p = publisher.publish(builder().content(Tck.bytes("x")).buildMessage(), ctx);
		Throwable failure = p.getFailure();
		assertThat(failure).as("publishing with " + feature).isInstanceOf(FeatureNotSupportedException.class);
		assertThat(((FeatureNotSupportedException) failure).getFeature()).isEqualTo(feature);
	}

	@Test
	void qos() throws Exception {
		assumeFalse(tck.has(FEATURE_QOS));
		MessageContext ctx = builder().channel(tck.channel("q")).extensionEntry(EXTENSION_QOS, QOS_AT_LEAST_ONCE).buildContext();
		publishFails(ctx, FEATURE_QOS);
		assertThatThrownBy(() -> subscription.subscribe(ctx)).isInstanceOf(FeatureNotSupportedException.class);
	}

	@Test
	void headers() throws Exception {
		assumeFalse(tck.has(FEATURE_HEADERS));
		publishFails(builder().channel(tck.channel("h")).header("k", "v").buildContext(), FEATURE_HEADERS);
	}

	@Test
	void contentType() throws Exception {
		assumeFalse(tck.has(FEATURE_CONTENT_TYPE));
		publishFails(builder().channel(tck.channel("c")).contentType("text/plain").buildContext(), FEATURE_CONTENT_TYPE);
	}

	@Test
	void timeToLive() throws Exception {
		assumeFalse(tck.has(FEATURE_TIME_TO_LIVE));
		publishFails(builder().channel(tck.channel("t")).extensionEntry(EXTENSION_TIME_TO_LIVE, 1000L).buildContext(),
				FEATURE_TIME_TO_LIVE);
	}

	@Test
	void retain() throws Exception {
		assumeFalse(tck.has(FEATURE_RETAIN));
		publishFails(builder().channel(tck.channel("r")).extensionEntry(EXTENSION_RETAIN, true).buildContext(), FEATURE_RETAIN);
	}

	@Test
	void channelExtension() throws Exception {
		assumeFalse(tck.has(FEATURE_CHANNEL_EXTENSION));
		publishFails(builder().channel(tck.channel("e"), "key").buildContext(), FEATURE_CHANNEL_EXTENSION);
	}

	@Test
	void queue() throws Exception {
		assumeFalse(tck.has(FEATURE_POINT_TO_POINT));
		MessageContext ctx = builder().channel(tck.channel("p")).extensionEntry(EXTENSION_CHANNEL_TYPE, CHANNEL_TYPE_QUEUE)
				.buildContext();
		assertThatThrownBy(() -> subscription.subscribe(ctx)).isInstanceOf(FeatureNotSupportedException.class);
	}

	@Test
	void replyTo() throws Exception {
		assumeFalse(tck.has(FEATURE_REPLY_TO));
		publishFails(builder().channel(tck.channel("rt")).correlationId("c").replyTo("x").buildContext(), FEATURE_REPLY_TO);
	}

	@Test
	void acknowledge() {
		assumeFalse(tck.has(FEATURE_ACKNOWLEDGE));
		assertThatThrownBy(() -> builder().adapt(AcknowledgeMessageContextBuilder.class)).isInstanceOf(FeatureNotSupportedException.class);
	}

	@Test
	void cloudEvents() {
		assumeFalse(tck.has(FEATURE_CLOUD_EVENTS));
		assertThatThrownBy(() -> builder().adapt(CloudEventMessageContextBuilder.class)).isInstanceOf(FeatureNotSupportedException.class);
	}

	@Test
	void unknownChannelTypeFails() throws Exception {
		MessageContext ctx = builder().channel(tck.channel("u")).extensionEntry(EXTENSION_CHANNEL_TYPE, "carrier-pigeon").buildContext();
		Throwable failure = publisher.publish(builder().content(Tck.bytes("x")).buildMessage(), ctx).getFailure();
		assertThat(failure).isInstanceOf(FeatureNotSupportedException.class);
	}

	@Test
	void wildcard() throws Exception {
		assumeFalse(tck.has(FEATURE_WILDCARD));
		// taken literally the symbol would subscribe to nothing; it must fail instead (MSG100a)
		assertThatThrownBy(() -> subscription.subscribe(tck.channel("w", tck.singleWildcard())))
				.isInstanceOf(FeatureNotSupportedException.class)
				.satisfies(e -> assertThat(((FeatureNotSupportedException) e).getFeature()).isEqualTo(FEATURE_WILDCARD));
	}

}
