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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.gecko.messaging.api.MessagingConstants.EXTENSION_BUFFER_SIZE;
import static org.gecko.messaging.api.MessagingConstants.EXTENSION_GROUP_HEADER;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContext;
import org.junit.jupiter.api.Test;
import org.osgi.util.pushstream.PushEvent;

class SubscriptionBufferTest {

	private static Message frame(int n, boolean key) {
		return new SimpleMessageContextBuilder().channel("video").header("keyframe", key)
				.content(ByteBuffer.wrap(((key ? "K" : "P") + n).getBytes(UTF_8))).buildMessage();
	}

	private static String text(Message m) {
		return UTF_8.decode(m.payload()).toString();
	}

	/**
	 * The stream takes from the queue without the lock of the buffer: it can get hold of a frame
	 * while the buffer discards the frames before it. That frame must not be delivered.
	 */
	@Test
	void aFrameTakenWhileItsGroupIsDiscardedIsDropped() throws Exception {
		MessageContext ctx = new SimpleMessageContextBuilder().channel("video").extensionEntry(EXTENSION_BUFFER_SIZE, 4)
				.extensionEntry(EXTENSION_GROUP_HEADER, "keyframe").buildContext();
		SubscriptionBuffer buffer = SubscriptionBuffer.of(new MessagingConfiguration(Map.of("osgi.messaging.name", "x")),
				"DISCARD_OLDEST", ctx);
		List<Message> raced = new ArrayList<>();
		// the stream wins the race for the element the buffer is about to remove
		LinkedBlockingQueue<PushEvent<? extends Message>> queue = new LinkedBlockingQueue<>(4) {
			private static final long serialVersionUID = 1L;

			@Override
			public boolean remove(Object o) {
				if (raced.isEmpty()) {
					raced.add(poll().getData());
					return false;
				}
				return super.remove(o);
			}
		};
		List<String> delivered = new ArrayList<>();
		buffer.doOffer(queue, PushEvent.data(frame(0, true)));
		buffer.doOffer(queue, PushEvent.data(frame(1, false)));
		for (int n = 0; n < 2; n++) {
			Message m = queue.poll().getData();
			if (buffer.taken(m)) {
				delivered.add(text(m));
			}
		}
		for (int n = 2; n < 6; n++) {
			buffer.doOffer(queue, PushEvent.data(frame(n, false)));
		}
		// full with P2..P5: P6 discards P2, and the stream takes P3 before the buffer removes it
		buffer.doOffer(queue, PushEvent.data(frame(6, false)));
		assertThat(raced).extracting(SubscriptionBufferTest::text).containsExactly("P3");
		buffer.doOffer(queue, PushEvent.data(frame(7, true)));
		buffer.doOffer(queue, PushEvent.data(frame(8, false)));
		List<Message> taken = new ArrayList<>(raced);
		for (PushEvent<? extends Message> e; (e = queue.poll()) != null;) {
			taken.add(e.getData());
		}
		for (Message m : taken) {
			if (buffer.taken(m)) {
				delivered.add(text(m));
			}
		}
		assertThat(delivered).as("no frame after a gap but a key frame").containsExactly("K0", "P1", "K7", "P8");
	}

}
