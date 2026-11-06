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
package org.gecko.messaging.core;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.acknowledge.AcknowledgeHandler;
import org.gecko.messaging.api.acknowledge.AcknowledgeMessageContext;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushEvent.EventType;
import org.osgi.util.pushstream.QueuePolicy;

/**
 * {@code DISCARD_OLDEST} of a subscription buffer that settles what it discards: the message is
 * counted as dropped, and one not decided yet is rejected without re-queue, so that it does not
 * hold a place of the broker's flow control ({@code receiveMaximum}, {@code prefetch}) for ever.
 * The plain policy of the push streams discards it without a word.
 */
final class DiscardOldestPolicy implements QueuePolicy<Message, BlockingQueue<PushEvent<? extends Message>>> {

	private final AtomicReference<SubscriptionHandle> handle;

	/**
	 * @param handle the subscription, set once the stream is connected
	 */
	DiscardOldestPolicy(AtomicReference<SubscriptionHandle> handle) {
		this.handle = handle;
	}

	@Override
	public void doOffer(BlockingQueue<PushEvent<? extends Message>> queue, PushEvent<? extends Message> event) {
		while (!queue.offer(event)) {
			PushEvent<? extends Message> oldest = queue.poll();
			if (oldest != null && oldest.getType() == EventType.DATA) {
				discarded(oldest.getData());
			}
		}
	}

	private void discarded(Message message) {
		SubscriptionHandle h = handle.get();
		if (h != null) {
			h.dropped();
		}
		if (message.getContext() instanceof AcknowledgeMessageContext ac) {
			AcknowledgeHandler ack = ac.getAcknowledgeHandler();
			if (ack instanceof AcknowledgeSupport support) {
				// an overload is no reason to hand the message to another receiver
				support.rejectFailed();
			} else if (ack != null) {
				ack.reject();
			}
		}
	}

}
