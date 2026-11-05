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
import static org.gecko.messaging.api.MessagingConstants.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessagePublisher;
import org.gecko.messaging.api.cloudevents.CloudEvent;
import org.gecko.messaging.api.cloudevents.CloudEventConstants;
import org.gecko.messaging.api.cloudevents.CloudEventMessageContext;
import org.gecko.messaging.api.cloudevents.CloudEventMessageContextBuilder;
import org.gecko.messaging.api.runtime.MessageServiceRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.ServiceReference;

/**
 * Messages as CloudEvents 1.0, when the instance is configured for them.
 */
public class CloudEventsTest extends AbstractMessagingTest {

	@BeforeEach
	void needsCloudEvents() {
		assumeTrue(tck.has(FEATURE_CLOUD_EVENTS));
	}

	@Test
	void attributesAndDataTravel() throws Exception {
		String channel = tck.channel("ce");
		List<Message> received = collect(channel);
		OffsetDateTime time = OffsetDateTime.parse("2026-11-05T12:00:00Z");
		Message m = builder().channel(channel).contentType("application/json").content(Tck.bytes("{\"temp\":21.5}"))
				.adapt(CloudEventMessageContextBuilder.class).id("e-1").type("org.example.measured").source("/sensors/7")
				.subject("temp").time(time).messageContextBuilder().buildMessage();
		assertThat(m.getContext()).isInstanceOf(CloudEventMessageContext.class);
		publisher.publish(m).getValue();
		tck.awaitSize(received, 1);
		assertThat(received.get(0).getContext()).isInstanceOf(CloudEventMessageContext.class);
		CloudEvent ce = ((CloudEventMessageContext) received.get(0).getContext()).getCloudEvent();
		assertThat(ce.getId()).isEqualTo("e-1");
		assertThat(ce.getType()).isEqualTo("org.example.measured");
		assertThat(ce.getSource()).isEqualTo("/sensors/7");
		assertThat(ce.getSubject()).isEqualTo("temp");
		assertThat(ce.getSpecVersion()).isEqualTo(CloudEventConstants.SPEC_VERSION);
		assertThat(ce.getTime()).isEqualTo(time);
		assertThat(received.get(0).getContext().getContentType()).isEqualTo("application/json");
		assertThat(Tck.text(received.get(0))).isEqualTo("{\"temp\":21.5}");
	}

	@Test
	void extensionsAreHeaders() throws Exception {
		assumeTrue(tck.has(FEATURE_HEADERS));
		String channel = tck.channel("ce", "ext");
		List<Message> received = collect(channel);
		publisher.publish(builder().channel(channel).header("tenant", "acme").content(Tck.bytes("x"))
				.adapt(CloudEventMessageContextBuilder.class).type("t").source("/s").messageContextBuilder().buildMessage()).getValue();
		tck.awaitSize(received, 1);
		CloudEventMessageContext ctx = (CloudEventMessageContext) received.get(0).getContext();
		assertThat(ctx.getCloudEvent().getExtensions()).containsEntry("tenant", "acme");
		assertThat(ctx.getHeaders()).containsEntry("tenant", "acme");
	}

	@Test
	void generatedIdAndTime() throws Exception {
		Message m = builder().channel(tck.channel("ce", "gen")).adapt(CloudEventMessageContextBuilder.class).type("t").source("/s")
				.messageContextBuilder().buildMessage();
		CloudEvent ce = ((CloudEventMessageContext) m.getContext()).getCloudEvent();
		assertThat(ce.getId()).isNotBlank();
		assertThat(ce.getTime()).isNotNull();
	}

	@Test
	void plainMessagesTravelAsCloudEventsToo() throws Exception {
		String channel = tck.channel("ce", "plain");
		List<Message> received = collect(channel);
		publisher.publish(text(channel, "plain")).getValue();
		tck.awaitSize(received, 1);
		assertThat(Tck.text(received.get(0))).isEqualTo("plain");
		assertThat(received.get(0).getContext()).isInstanceOf(CloudEventMessageContext.class);
	}

	@Test
	void aMessageThatIsNoCloudEventIsDroppedByDefault() throws Exception {
		Object acceptPlain = tck.reference(MessageServiceRuntime.class).getProperty("cloudEvents.acceptPlain");
		assumeFalse(Boolean.TRUE.equals(acceptPlain) || "true".equals(acceptPlain), "configured to accept plain messages");
		MessagePublisher plain = plainPublisherOnTheSameBroker();
		assumeTrue(plain != null, "no instance without CloudEvents on the same broker in this run");
		String channel = tck.channel("ce", "strict");
		List<Message> received = collect(channel);
		MessageServiceRuntime runtime = tck.service(MessageServiceRuntime.class);
		tck.await("subscribed", () -> Arrays.stream(runtime.getRuntimeDTO().subscriptions)
				.anyMatch(d -> channel.equals(d.channel.name) && d.channel.connected));
		plain.publish(builder().channel(channel).content(Tck.bytes("{\"temp\": 21}")).buildMessage()).getValue();
		tck.await("dropped", () -> Arrays.stream(runtime.getRuntimeDTO().subscriptions)
				.anyMatch(d -> channel.equals(d.channel.name) && d.droppedMessages == 1));
		publisher.publish(text(channel, "event")).getValue();
		tck.awaitSize(received, 1);
		assertThat(received).extracting(Tck::text).as("only the CloudEvent").containsExactly("event");
	}

	/** @return the publisher of another instance on the same broker without CloudEvents, or {@code null} */
	private MessagePublisher plainPublisherOnTheSameBroker() throws Exception {
		Object uri = tck.reference(MessageServiceRuntime.class).getProperty("uri");
		Object name = tck.reference(MessageServiceRuntime.class).getProperty(MESSAGING_NAME_PROPERTY);
		for (ServiceReference<MessagePublisher> r : bundleContext.getServiceReferences(MessagePublisher.class, null)) {
			Object features = r.getProperty(MESSAGING_FEATURE_PROPERTY);
			boolean cloudEvents = features instanceof String[] f && Arrays.asList(f).contains(FEATURE_CLOUD_EVENTS);
			if (uri != null && uri.equals(r.getProperty("uri")) && !name.equals(r.getProperty(MESSAGING_NAME_PROPERTY))
					&& !cloudEvents) {
				return bundleContext.getService(r);
			}
		}
		return null;
	}

}
