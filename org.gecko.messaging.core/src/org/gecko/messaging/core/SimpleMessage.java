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

import static java.util.Objects.requireNonNull;

import java.nio.ByteBuffer;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContext;

/**
 * Immutable {@link Message}. The payload is copied once on creation, so neither the creator nor any
 * consumer can change it afterwards.
 */
public final class SimpleMessage implements Message {

	private static final ByteBuffer EMPTY = ByteBuffer.allocate(0).asReadOnlyBuffer();

	private final ByteBuffer payload;
	private final MessageContext context;

	/**
	 * Creates a message over a copy of the remaining content of the given buffer.
	 *
	 * @param payload the payload, {@code null} for none; the buffer itself is not changed
	 * @param context the context, must not be {@code null}
	 */
	public SimpleMessage(ByteBuffer payload, MessageContext context) {
		this(copy(payload), context, true);
	}

	private SimpleMessage(ByteBuffer ownedPayload, MessageContext context, boolean owned) {
		this.payload = ownedPayload;
		this.context = requireNonNull(context, "context");
	}

	/**
	 * Creates a message that takes the given array as its payload without copying it. Only for
	 * arrays nobody else holds, like those a protocol client just read from the network.
	 *
	 * @param payload the payload array, {@code null} for none
	 * @param context the context
	 * @return the message
	 */
	public static SimpleMessage ofOwned(byte[] payload, MessageContext context) {
		return new SimpleMessage(payload == null || payload.length == 0 ? EMPTY : ByteBuffer.wrap(payload).asReadOnlyBuffer(), context,
				true);
	}

	/**
	 * Returns a message with the same payload and another context.
	 *
	 * @param newContext the context
	 * @return the message
	 */
	public SimpleMessage withContext(MessageContext newContext) {
		return new SimpleMessage(payload, newContext, true);
	}

	@Override
	public ByteBuffer payload() {
		return payload.duplicate();
	}

	@Override
	public MessageContext getContext() {
		return context;
	}

	/**
	 * Returns the payload as a new byte array.
	 *
	 * @param message the message
	 * @return the bytes of the payload
	 */
	public static byte[] bytes(Message message) {
		ByteBuffer buffer = message.payload();
		byte[] bytes = new byte[buffer.remaining()];
		buffer.get(bytes);
		return bytes;
	}

	private static ByteBuffer copy(ByteBuffer source) {
		if (source == null || !source.hasRemaining()) {
			return EMPTY;
		}
		ByteBuffer copy = ByteBuffer.allocate(source.remaining());
		copy.put(source.duplicate());
		copy.flip();
		return copy.asReadOnlyBuffer();
	}

	@Override
	public String toString() {
		return "Message[" + payload.remaining() + " bytes, " + context + "]";
	}

}
