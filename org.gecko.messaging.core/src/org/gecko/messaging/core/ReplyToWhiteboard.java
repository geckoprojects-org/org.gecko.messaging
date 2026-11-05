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

import static org.gecko.messaging.api.MessagingConstants.EXTENSION_CHANNEL_TYPE;
import static org.gecko.messaging.api.MessagingConstants.FEATURE_REPLY_TO_MANY;
import static org.gecko.messaging.api.MessagingConstants.HEADER_REPLY_COMPLETE;
import static org.gecko.messaging.api.MessagingConstants.MESSAGING_TARGET_PROPERTY;
import static org.gecko.messaging.api.replyto.ReplyToSubscriptionConstants.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContextBuilder;
import org.gecko.messaging.api.dto.ReplyToSubscriptionDTO;
import org.gecko.messaging.api.replyto.ReplyToManySubscriptionHandler;
import org.gecko.messaging.api.replyto.ReplyToSingleSubscriptionHandler;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.dto.ServiceReferenceDTO;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.tracker.ServiceTracker;

/**
 * The reply-to whiteboard of one instance: binds the handlers whose target selects the instance,
 * subscribes to their request channels and publishes their responses.
 */
final class ReplyToWhiteboard {

	private static final Logger LOG = Logger.getLogger(ReplyToWhiteboard.class.getName());

	private final AbstractMessagingInstance instance;
	private final ServiceTracker<Object, Binding> tracker;
	private final Map<ServiceReference<Object>, Binding> bindings = new ConcurrentHashMap<>();

	private final class Binding {

		final ServiceReference<Object> reference;
		final Object handler;
		final String channel;
		final String channelExtension;
		final String replyChannel;
		final boolean many;
		final AtomicLong handled = new AtomicLong();
		volatile SubscriptionHandle handle;

		Binding(ServiceReference<Object> reference, Object handler, String channel, String channelExtension, String replyChannel) {
			this.reference = reference;
			this.handler = handler;
			this.channel = channel;
			this.channelExtension = channelExtension;
			this.replyChannel = replyChannel;
			this.many = handler instanceof ReplyToManySubscriptionHandler;
		}

	}

	ReplyToWhiteboard(AbstractMessagingInstance instance, BundleContext context) {
		this.instance = instance;
		Filter filter;
		try {
			filter = FrameworkUtil.createFilter("(&(|(" + Constants.OBJECTCLASS + "=" + ReplyToSingleSubscriptionHandler.class.getName()
					+ ")(" + Constants.OBJECTCLASS + "=" + ReplyToManySubscriptionHandler.class.getName() + "))("
					+ REPLY_TO_SUBSCRIPTION_CHANNEL_PROPERTY + "=*)(" + MESSAGING_TARGET_PROPERTY + "=*))");
		} catch (InvalidSyntaxException e) {
			throw new IllegalStateException(e);
		}
		this.tracker = new ServiceTracker<>(context, filter, null) {
			@Override
			public Binding addingService(ServiceReference<Object> reference) {
				if (!instance.targets(reference)) {
					return null;
				}
				Object handler = context.getService(reference);
				if (handler == null) {
					return null;
				}
				Binding b = bind(reference, handler);
				if (b == null) {
					context.ungetService(reference);
				}
				return b;
			}

			@Override
			public void modifiedService(ServiceReference<Object> reference, Binding binding) {
				removedService(reference, binding);
				Binding b = addingService(reference);
				if (b == null) {
					remove(reference);
				}
			}

			@Override
			public void removedService(ServiceReference<Object> reference, Binding binding) {
				unbind(binding);
				context.ungetService(reference);
			}
		};
	}

	void open() {
		tracker.open();
	}

	void close() {
		tracker.close();
	}

	private Binding bind(ServiceReference<Object> reference, Object handler) {
		String channel = string(reference, REPLY_TO_SUBSCRIPTION_CHANNEL_PROPERTY);
		Binding b = new Binding(reference, handler, channel, string(reference, REPLY_TO_SUBSCRIPTION_CHANNEL_EXTENSION_PROPERTY),
				string(reference, REPLY_TO_SUBSCRIPTION_REPLY_CHANNEL_PROPERTY));
		if (b.many && !instance.getFeatures().has(FEATURE_REPLY_TO_MANY)) {
			LOG.warning(() -> "Messaging instance " + instance.getName() + " cannot stream responses; handler " + reference + " not bound");
			return null;
		}
		MessageContextBuilder ctx = new SimpleMessageContextBuilder().channel(channel, b.channelExtension);
		String type = string(reference, REPLY_TO_SUBSCRIPTION_CHANNEL_TYPE_PROPERTY);
		if (type != null) {
			ctx.extensionEntry(EXTENSION_CHANNEL_TYPE, type);
		}
		try {
			SimpleMessageContext sub = (SimpleMessageContext) ctx.buildContext();
			instance.getFeatures().checkSubscribe(sub);
			b.handle = instance.addSubscription(sub, new SubscriptionHandle.Sink() {
				@Override
				public long accept(Message message) {
					instance.workers().execute(() -> handle(b, message));
					return 0;
				}

				@Override
				public void closed(Throwable failure) {
					if (failure != null) {
						LOG.log(Level.WARNING, "The request subscription of handler " + reference + " ended", failure);
					}
				}
			}, true);
		} catch (Exception e) {
			LOG.log(Level.WARNING, "Could not bind reply-to handler " + reference + " on " + instance.getName(), e);
			return null;
		}
		bindings.put(reference, b);
		return b;
	}

	private void unbind(Binding b) {
		bindings.remove(b.reference);
		SubscriptionHandle h = b.handle;
		if (h != null) {
			instance.closeSubscription(h, null);
		}
	}

	private void handle(Binding b, Message request) {
		String reply = request.getContext().getReplyToChannel() != null ? request.getContext().getReplyToChannel() : b.replyChannel;
		b.handled.incrementAndGet();
		try {
			if (b.many) {
				PushStream<Message> responses = ((ReplyToManySubscriptionHandler) b.handler).handleResponses(request,
						() -> responseBuilder(request, reply));
				if (responses == null) {
					return;
				}
				if (reply == null) {
					responses.close();
					return;
				}
				responses.forEach(m -> instance.publish(m, null)).onResolve(() -> {
					MessageContextBuilder end = responseBuilder(request, reply).header(HEADER_REPLY_COMPLETE, Boolean.TRUE);
					instance.publish(end.buildMessage(), null);
				});
			} else {
				Message response = ((ReplyToSingleSubscriptionHandler) b.handler).handleResponse(request, responseBuilder(request, reply));
				if (response != null && reply != null) {
					instance.publish(response, null).onFailure(
							t -> LOG.log(Level.WARNING, "Could not publish the response of " + b.reference, t));
				}
			}
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Reply-to handler " + b.reference + " failed", e);
		}
	}

	private MessageContextBuilder responseBuilder(Message request, String reply) {
		MessageContextBuilder builder = instance.newBuilder();
		if (reply != null) {
			instance.configureResponse(builder, request.getContext(), reply);
		}
		return builder;
	}

	ReplyToSubscriptionDTO[] toDTOs() {
		return bindings.values().stream().map(b -> {
			ReplyToSubscriptionDTO dto = new ReplyToSubscriptionDTO();
			try {
				dto.handlerService = b.reference.adapt(ServiceReferenceDTO.class);
			} catch (IllegalStateException e) {
				// unregistered meanwhile
			}
			SubscriptionHandle h = b.handle;
			dto.requestChannel = SubscriptionHandle.channelDTO(b.channel, b.channelExtension, h != null && !h.isClosed());
			dto.replyChannel = b.replyChannel == null ? null : SubscriptionHandle.channelDTO(b.replyChannel, null, false);
			dto.many = b.many;
			dto.handledRequests = b.handled.get();
			return dto;
		}).toArray(ReplyToSubscriptionDTO[]::new);
	}

	private static String string(ServiceReference<?> reference, String key) {
		Object v = reference.getProperty(key);
		if (v instanceof String[] a) {
			v = a.length == 0 ? null : a[0];
		}
		return v == null || v.toString().isEmpty() ? null : v.toString();
	}

}
