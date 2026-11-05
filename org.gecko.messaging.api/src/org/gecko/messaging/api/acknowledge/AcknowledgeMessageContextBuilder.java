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
package org.gecko.messaging.api.acknowledge;

import java.util.function.Consumer;
import java.util.function.Predicate;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContextBuilder;
import org.osgi.annotation.versioning.ProviderType;

/**
 * Configures the acknowledgement of a subscription. Obtained with
 * {@code MessageContextBuilder.adapt(AcknowledgeMessageContextBuilder.class)} from instances that
 * support {@link org.gecko.messaging.api.MessagingConstants#FEATURE_ACKNOWLEDGE}. The built
 * context is an {@link AcknowledgeMessageContext}.
 */
@ProviderType
public interface AcknowledgeMessageContextBuilder {

	/**
	 * Acknowledges the messages the filter accepts and rejects the others, before the stream.
	 *
	 * @param filter the filter
	 * @return this builder
	 */
	AcknowledgeMessageContextBuilder acknowledgeFilter(Predicate<Message> filter);

	/**
	 * Lets the function decide before the stream, with the {@link AcknowledgeHandler} of the
	 * message's {@link AcknowledgeMessageContext}.
	 *
	 * @param function the function
	 * @return this builder
	 */
	AcknowledgeMessageContextBuilder acknowledgeFunction(Consumer<Message> function);

	/**
	 * The consumer acknowledges in the stream.
	 *
	 * @return this builder
	 */
	AcknowledgeMessageContextBuilder manualAcknowledge();

	/**
	 * Re-queues rejected messages instead of dead-lettering or discarding them.
	 *
	 * @param requeue {@code true} to re-queue
	 * @return this builder
	 */
	AcknowledgeMessageContextBuilder requeueOnReject(boolean requeue);

	/**
	 * Sets the consumer called after a message was acknowledged.
	 *
	 * @param consumer the consumer
	 * @return this builder
	 */
	AcknowledgeMessageContextBuilder postAcknowledge(Consumer<Message> consumer);

	/**
	 * Sets the consumer called after a message was rejected.
	 *
	 * @param consumer the consumer
	 * @return this builder
	 */
	AcknowledgeMessageContextBuilder postReject(Consumer<Message> consumer);

	/**
	 * Returns the builder this one was adapted from.
	 *
	 * @return the message context builder
	 */
	MessageContextBuilder messageContextBuilder();

}
