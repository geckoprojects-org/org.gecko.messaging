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
import org.gecko.messaging.api.MessageContext;
import org.osgi.annotation.versioning.ProviderType;

/**
 * A message context with acknowledgement.
 * <p>
 * On a subscription context it carries the acknowledgement configuration; on the context of a
 * received message the {@link #getAcknowledgeHandler() handler} for that message.
 * <p>
 * Acknowledgement modes of a subscription, decided in this order:
 * <ol>
 * <li>an {@link #getAcknowledgeFunction() acknowledge function}: called for each message before
 * it enters the stream, decides itself with the message's {@link AcknowledgeHandler};</li>
 * <li>an {@link #getAcknowledgeFilter() acknowledge filter}: messages it accepts are acknowledged,
 * the others rejected — before the stream; rejected messages do not enter the stream;</li>
 * <li>{@link #isManualAcknowledge() manual}: the consumer acknowledges in the stream; messages
 * left open when the stream closes are rejected;</li>
 * <li>otherwise every message is acknowledged on receipt.</li>
 * </ol>
 * After an acknowledgement or rejection the post consumers are called.
 */
@ProviderType
public interface AcknowledgeMessageContext extends MessageContext {

	/**
	 * Returns the handler for the received message this context belongs to.
	 *
	 * @return the handler, or {@code null} on a subscription context
	 */
	AcknowledgeHandler getAcknowledgeHandler();

	/**
	 * Returns the acknowledgement state of the received message.
	 *
	 * @return the state, {@link AcknowledgeType#UNSUPPORTED} on a subscription context
	 */
	AcknowledgeType getAcknowledgeState();

	/**
	 * Returns the filter that decides between acknowledgement and rejection before the stream.
	 *
	 * @return the filter, or {@code null}
	 */
	Predicate<Message> getAcknowledgeFilter();

	/**
	 * Returns the function that decides about acknowledgement before the stream.
	 *
	 * @return the function, or {@code null}
	 */
	Consumer<Message> getAcknowledgeFunction();

	/**
	 * Returns whether the consumer acknowledges in the stream.
	 *
	 * @return {@code true} for manual acknowledgement
	 */
	boolean isManualAcknowledge();

	/**
	 * Returns whether a rejected message is to be re-queued instead of dead-lettered or discarded.
	 *
	 * @return {@code true} to re-queue
	 */
	boolean isRequeueOnReject();

	/**
	 * Returns the consumer called after a message was acknowledged.
	 *
	 * @return the consumer, or {@code null}
	 */
	Consumer<Message> getPostAcknowledge();

	/**
	 * Returns the consumer called after a message was rejected.
	 *
	 * @return the consumer, or {@code null}
	 */
	Consumer<Message> getPostReject();

}
