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

import org.gecko.messaging.api.MessageContextBuilder;
import org.gecko.messaging.api.MessagePublisher;
import org.gecko.messaging.api.MessageSubscription;
import org.gecko.messaging.api.acknowledge.AcknowledgeMessageContextBuilder;
import org.gecko.messaging.api.cloudevents.CloudEventMessageContextBuilder;
import org.gecko.messaging.api.dto.MessagingRuntimeDTO;
import org.gecko.messaging.api.replyto.ReplyToPublisher;
import org.gecko.messaging.api.runtime.ConnectionState;
import org.gecko.messaging.api.runtime.MessageServiceRuntime;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceObjects;
import org.osgi.framework.ServiceReference;

/**
 * An instance provides its services with the instance properties (MSG050, MSG095, MSG060).
 */
public class InstanceServicesTest extends AbstractMessagingTest {

	@Test
	void everyServiceCarriesTheInstanceProperties() {
		for (Class<?> type : new Class<?>[] { MessagePublisher.class, MessageSubscription.class, MessageContextBuilder.class,
				MessageServiceRuntime.class }) {
			tck.service(type);
			ServiceReference<?> ref = tck.reference(type);
			assertThat(ref).as(type.getSimpleName()).isNotNull();
			assertThat(ref.getProperty(MESSAGING_NAME_PROPERTY)).as("name of " + type.getSimpleName()).isNotNull();
			assertThat(ref.getProperty(MESSAGING_PROVIDER_PROPERTY)).as("provider of " + type.getSimpleName()).isNotNull();
			assertThat(ref.getProperty(MESSAGING_PROTOCOL_PROPERTY)).as("protocol of " + type.getSimpleName()).isNotNull();
			assertThat(ref.getProperty(MESSAGING_FEATURE_PROPERTY)).as("features of " + type.getSimpleName()).isNotNull();
		}
	}

	@Test
	void builderIsPrototypeScoped() {
		tck.service(MessageContextBuilder.class);
		ServiceReference<MessageContextBuilder> ref = tck.reference(MessageContextBuilder.class);
		assertThat(ref.getProperty(Constants.SERVICE_SCOPE)).isEqualTo(Constants.SCOPE_PROTOTYPE);
		ServiceObjects<MessageContextBuilder> objects = bundleContext.getServiceObjects(ref);
		MessageContextBuilder a = objects.getService();
		MessageContextBuilder b = objects.getService();
		try {
			assertThat(a).isNotSameAs(b);
		} finally {
			objects.ungetService(a);
			objects.ungetService(b);
		}
	}

	@Test
	void replyToPublisherExactlyWhenAnnounced() {
		boolean announced = tck.has(FEATURE_REPLY_TO);
		assertThat(tck.reference(ReplyToPublisher.class) != null).as("ReplyToPublisher registered").isEqualTo(announced);
	}

	@Test
	void runtimeDescribesTheInstance() {
		MessageServiceRuntime runtime = tck.service(MessageServiceRuntime.class);
		ServiceReference<MessageServiceRuntime> ref = tck.reference(MessageServiceRuntime.class);
		MessagingRuntimeDTO dto = runtime.getRuntimeDTO();
		assertThat(dto.serviceDTO).isNotNull();
		assertThat(dto.serviceDTO.id).isEqualTo((Long) ref.getProperty(Constants.SERVICE_ID));
		assertThat(dto.name).isEqualTo(ref.getProperty(MESSAGING_NAME_PROPERTY));
		assertThat(dto.providerName).isEqualTo(ref.getProperty(MESSAGING_PROVIDER_PROPERTY));
		assertThat(dto.features).containsExactlyInAnyOrderElementsOf(tck.features());
		assertThat(dto.connectionState).isEqualTo(ConnectionState.CONNECTED.name());
		assertThat(runtime.getConnectionState()).isEqualTo(ConnectionState.CONNECTED);
		if (dto.connectionURI != null) {
			assertThat(dto.connectionURI).as("no credentials in the URI").doesNotContain("@");
		}
	}

	@Test
	void buildersAdaptOnlyToAnnouncedFeatures() {
		assertThat(builder().isAdaptable(AcknowledgeMessageContextBuilder.class))
				.isEqualTo(tck.has(FEATURE_ACKNOWLEDGE));
		assertThat(builder().isAdaptable(CloudEventMessageContextBuilder.class))
				.isEqualTo(tck.has(FEATURE_CLOUD_EVENTS));
	}

}
