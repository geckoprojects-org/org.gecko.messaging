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

import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.gecko.messaging.api.FeatureNotSupportedException;
import org.gecko.messaging.api.MessageContextBuilder;
import org.gecko.messaging.api.MessagingException;
import org.gecko.messaging.api.cloudevents.CloudEvent;
import org.gecko.messaging.api.cloudevents.CloudEventConstants;
import org.gecko.messaging.api.cloudevents.CloudEventMessageContext;
import org.gecko.messaging.core.cloudevents.CloudEventCodec;
import org.osgi.util.promise.Promise;

/**
 * How an instance puts messages on the wire and takes them off: plain, or as CloudEvents in the
 * binary or the structured mode. Chosen once, when the instance starts, so that no message pays
 * for looking up the mode.
 */
abstract class CloudEventBinding {

	/** The protocol's publish, {@link AbstractMessagingInstance#doPublish}. */
	@FunctionalInterface
	interface Wire {
		Promise<Void> publish(SimpleMessageContext context, ByteBuffer payload) throws Exception;
	}

	/** Plain messages, no CloudEvents. */
	static final CloudEventBinding NONE = new CloudEventBinding() {
		@Override
		Promise<Void> publish(SimpleMessageContext ctx, ByteBuffer payload, Wire wire) throws Exception {
			return wire.publish(ctx, payload);
		}

		@Override
		SimpleMessage decode(SimpleMessageContext ctx, byte[] payload) {
			return SimpleMessage.ofOwned(payload, ctx);
		}
	};

	/**
	 * Returns the binding of a configuration.
	 *
	 * @param config the configuration
	 * @param headerPrefix the protocol's header prefix of the binary mode, asked only for it
	 * @return the binding
	 * @throws MessagingException if the binary mode is configured on a protocol without headers
	 */
	static CloudEventBinding of(MessagingConfiguration config, Supplier<String> headerPrefix) {
		String mode = config.cloudEventsMode();
		if (CloudEventConstants.MODE_STRUCTURED.equals(mode)) {
			return new Structured(config);
		}
		if (CloudEventConstants.MODE_BINARY.equals(mode)) {
			try {
				return new Binary(config, headerPrefix.get());
			} catch (FeatureNotSupportedException e) {
				// refused before the instance announces cloudEvents: otherwise every message would fail
				throw new MessagingException("Messaging instance " + config.name() + ": " + e.getMessage()
						+ ", use cloudEvents=structured", e);
			}
		}
		return NONE;
	}

	/**
	 * Publishes a message.
	 *
	 * @param ctx the context, features checked
	 * @param payload the payload
	 * @param wire the protocol's publish
	 * @return the promise of the hand-over
	 * @throws Exception if publishing failed at once
	 */
	abstract Promise<Void> publish(SimpleMessageContext ctx, ByteBuffer payload, Wire wire) throws Exception;

	/**
	 * Decodes a received message.
	 *
	 * @param ctx the context of the message as received
	 * @param payload the payload, owned from now on
	 * @return the message, or {@code null} if it is no CloudEvent and the instance takes
	 *         CloudEvents only
	 * @throws RuntimeException if the message announces a CloudEvent it is not
	 */
	abstract SimpleMessage decode(SimpleMessageContext ctx, byte[] payload);

	/** The CloudEvents modes: the event of a message and plain messages on request. */
	private abstract static class Events extends CloudEventBinding {

		private final String source;
		private final String type;
		/** Secure by default: CloudEvents only; plain messages on request, unchanged. */
		private final boolean acceptPlain;

		Events(MessagingConfiguration config) {
			this.source = config.string("cloudEvents.source", "/osgi/messaging/" + config.name());
			this.type = config.string("cloudEvents.type", "org.gecko.messaging.message");
			this.acceptPlain = config.cloudEventsAcceptPlain();
		}

		CloudEvent event(SimpleMessageContext ctx) {
			if (ctx instanceof CloudEventMessageContext cem) {
				return cem.getCloudEvent();
			}
			return new SimpleCloudEvent(UUID.randomUUID().toString(), source, type, null, null, OffsetDateTime.now(),
					ctx.getHeaders());
		}

		/** @return the message as it is if plain messages are accepted, nothing is made up for it */
		SimpleMessage plain(SimpleMessageContext ctx, byte[] payload) {
			return acceptPlain ? SimpleMessage.ofOwned(payload, ctx) : null;
		}

	}

	/** The structured mode: the event and the message in one JSON envelope. */
	private static final class Structured extends Events {

		Structured(MessagingConfiguration config) {
			super(config);
		}

		@Override
		Promise<Void> publish(SimpleMessageContext ctx, ByteBuffer payload, Wire wire) throws Exception {
			byte[] envelope = CloudEventCodec.toStructured(event(ctx), ctx, payload);
			SimpleMessageContextBuilder b = new SimpleMessageContextBuilder();
			b.withContext(ctx);
			b.contentType(CloudEventConstants.STRUCTURED_JSON_CONTENT_TYPE);
			b.contentEncoding(null);
			return wire.publish(b.buildSimple(), ByteBuffer.wrap(envelope));
		}

		@Override
		SimpleMessage decode(SimpleMessageContext ctx, byte[] payload) {
			String ct = ctx.getContentType();
			CloudEventCodec.Structured s;
			if (ct != null) {
				if (!ct.toLowerCase(Locale.ROOT).startsWith(CloudEventConstants.STRUCTURED_JSON_CONTENT_TYPE)) {
					return plain(ctx, payload);
				}
				// announced as envelope: one that is not is an error, not a plain message
				s = CloudEventCodec.fromStructured(payload);
			} else {
				// no content type (MQTT 3.1.1, WebSocket): an envelope is what parses as one
				if (!looksLikeJsonObject(payload)) {
					return plain(ctx, payload);
				}
				try {
					s = CloudEventCodec.fromStructured(payload);
				} catch (MessagingException e) {
					return plain(ctx, payload);
				}
			}
			MessageContextBuilder b = new SimpleMessageContextBuilder().withContext(ctx).contentType(s.contentType())
					.contentEncoding(s.contentEncoding());
			if (ctx.getCorrelationId() == null) {
				b.correlationId(s.correlationId());
			}
			if (ctx.getReplyToChannel() == null) {
				b.replyTo(s.replyTo());
			}
			return SimpleMessage.ofOwned(s.data(), SimpleMessageContextBuilder.cloudEventContext(b.buildContext(), s.cloudEvent()));
		}

		private static boolean looksLikeJsonObject(byte[] payload) {
			for (byte b : payload) {
				if (!Character.isWhitespace(b)) {
					return b == '{';
				}
			}
			return false;
		}

	}

	/** The binary mode: the attributes of the event as headers with the protocol's prefix. */
	private static final class Binary extends Events {

		private final String prefix;

		Binary(MessagingConfiguration config, String prefix) {
			super(config);
			this.prefix = prefix;
		}

		@Override
		Promise<Void> publish(SimpleMessageContext ctx, ByteBuffer payload, Wire wire) throws Exception {
			Map<String, Object> headers = CloudEventCodec.toBinaryHeaders(event(ctx), prefix);
			SimpleMessageContext wireContext = new SimpleMessageContextBuilder().channel(ctx.getChannel(), ctx.getChannelExtension())
					.contentType(ctx.getContentType()).contentEncoding(ctx.getContentEncoding()).correlationId(ctx.getCorrelationId())
					.replyTo(ctx.getReplyToChannel()).extensions(ctx.getExtensions()).headers(headers).buildSimple();
			return wire.publish(wireContext, payload);
		}

		@Override
		SimpleMessage decode(SimpleMessageContext ctx, byte[] payload) {
			CloudEventCodec.Binary bin = CloudEventCodec.fromBinaryHeaders(ctx.getHeaders(), prefix);
			if (bin.cloudEvent() == null) {
				return plain(ctx, payload);
			}
			SimpleMessageContextBuilder b = new SimpleMessageContextBuilder();
			b.withContext(ctx);
			ctx.getHeaders().keySet().forEach(k -> b.header(k, null));
			b.headers(bin.otherHeaders());
			return SimpleMessage.ofOwned(payload, SimpleMessageContextBuilder.cloudEventContext(b.buildContext(), bin.cloudEvent()));
		}

	}

}
