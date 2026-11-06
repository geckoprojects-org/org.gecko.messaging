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
import static org.gecko.messaging.api.MessagingConstants.*;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.MessageContextBuilder;
import org.gecko.messaging.api.MessagePublisher;
import org.gecko.messaging.api.MessageSubscription;
import org.gecko.messaging.api.dto.MessagingRuntimeDTO;
import org.gecko.messaging.api.replyto.ReplyToPublisher;
import org.gecko.messaging.api.runtime.ConnectionListener;
import org.gecko.messaging.api.runtime.ConnectionState;
import org.gecko.messaging.api.runtime.MessageServiceRuntime;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.PrototypeServiceFactory;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.dto.ServiceReferenceDTO;
import org.osgi.util.promise.Promise;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.tracker.ServiceTracker;

/**
 * The OSGi side of an instance: its services and their properties, the target filters, the
 * connection listener whiteboard and the reply-to whiteboard. An instance runs without it
 * ({@link AbstractMessagingInstance#startStandalone()}).
 */
final class OsgiBinding {

	private static final Logger LOG = Logger.getLogger(OsgiBinding.class.getName());

	private final AbstractMessagingInstance instance;
	private final BundleContext context;
	private final List<ServiceRegistration<?>> registrations = new ArrayList<>();
	private volatile ServiceRegistration<MessageServiceRuntime> runtimeRegistration;
	private ServiceTracker<ConnectionListener, ConnectionListener> listenerTracker;
	private ReplyToWhiteboard whiteboard;

	OsgiBinding(AbstractMessagingInstance instance, BundleContext context) {
		this.instance = requireNonNull(instance);
		this.context = requireNonNull(context);
	}

	/** Registers the services and opens the whiteboards. */
	void open() {
		Dictionary<String, Object> props = serviceProperties();
		registrations.add(context.registerService(MessagePublisher.class, new Publisher(), props));
		registrations.add(context.registerService(MessageSubscription.class, new Subscription(), props));
		registrations.add(context.registerService(MessageContextBuilder.class.getName(), new BuilderFactory(), props));
		if (instance.getFeatures().has(FEATURE_REPLY_TO)) {
			registrations.add(context.registerService(ReplyToPublisher.class, instance.replyToPublisher(), props));
		}
		ServiceRegistration<MessageServiceRuntime> runtime = context.registerService(MessageServiceRuntime.class, new Runtime(), props);
		runtimeRegistration = runtime;
		registrations.add(runtime);
		listenerTracker = new ServiceTracker<>(context, ConnectionListener.class, null) {
			@Override
			public ConnectionListener addingService(ServiceReference<ConnectionListener> reference) {
				if (!targets(reference)) {
					return null;
				}
				ConnectionListener l = super.addingService(reference);
				if (l != null) {
					instance.addConnectionListener(l);
				}
				return l;
			}

			@Override
			public void removedService(ServiceReference<ConnectionListener> reference, ConnectionListener service) {
				instance.removeConnectionListener(service);
				super.removedService(reference, service);
			}
		};
		listenerTracker.open();
		if (instance.getFeatures().has(FEATURE_REPLY_TO_SUBSCRIPTION)) {
			whiteboard = new ReplyToWhiteboard(instance, context);
			whiteboard.open();
		}
	}

	/** Closes the reply-to whiteboard and unregisters the services. */
	void unregister() {
		if (whiteboard != null) {
			whiteboard.close();
		}
		for (ServiceRegistration<?> r : registrations) {
			try {
				r.unregister();
			} catch (IllegalStateException e) {
				// already gone with the bundle
			}
		}
		registrations.clear();
	}

	/** Stops tracking connection listeners: after the last event, the one of the close. */
	void closeListeners() {
		if (listenerTracker != null) {
			listenerTracker.close();
		}
	}

	/** Configuration keys that name credentials and stay out of the service properties. */
	static boolean isSecret(String key) {
		String k = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(".", "");
		return k.contains("password") || k.contains("secret") || k.contains("token") || k.contains("credential")
				|| k.contains("apikey") || k.contains("privatekey");
	}

	private Dictionary<String, Object> serviceProperties() {
		MessagingConfiguration config = instance.getConfiguration();
		Hashtable<String, Object> props = new Hashtable<>();
		for (Map.Entry<String, Object> e : config.raw().entrySet()) {
			String k = e.getKey();
			if (k.startsWith(".") || k.startsWith("component.") || k.startsWith("service.") || k.equals("objectClass")
					|| isSecret(k) || e.getValue() == null) {
				continue;
			}
			props.put(k, e.getValue());
		}
		// every bundle sees the service properties, and the runtime DTO shows them
		if (config.uri() != null) {
			props.put("uri", config.displayUri());
		}
		props.put(MESSAGING_NAME_PROPERTY, config.name());
		props.put(MESSAGING_PROVIDER_PROPERTY, AbstractMessagingInstance.PROVIDER);
		props.put(MESSAGING_PROTOCOL_PROPERTY, new String[] { instance.protocol() });
		props.put(MESSAGING_FEATURE_PROPERTY, instance.getFeatures().asSet().toArray(new String[0]));
		if (instance.maxMessageSize() > 0) {
			props.put(MESSAGING_MAX_MESSAGE_SIZE_PROPERTY, instance.maxMessageSize());
		}
		return props;
	}

	/**
	 * @param reference a service that may name a messaging target
	 * @return {@code true} if it has no target or its target matches this instance
	 */
	boolean targets(ServiceReference<?> reference) {
		Object target = reference.getProperty(MESSAGING_TARGET_PROPERTY);
		return target == null || matches(target.toString());
	}

	private boolean matches(String target) {
		try {
			Filter filter = FrameworkUtil.createFilter(target);
			ServiceRegistration<MessageServiceRuntime> r = runtimeRegistration;
			return r != null && filter.match(r.getReference());
		} catch (InvalidSyntaxException | IllegalStateException e) {
			LOG.log(Level.WARNING, "Invalid messaging target " + target, e);
			return false;
		}
	}

	/** Fills in the OSGi part of the runtime DTO: the service and the reply-to whiteboard. */
	void fill(MessagingRuntimeDTO dto) {
		ServiceRegistration<MessageServiceRuntime> r = runtimeRegistration;
		if (r != null) {
			try {
				dto.serviceDTO = r.getReference().adapt(ServiceReferenceDTO.class);
			} catch (IllegalStateException e) {
				// unregistered meanwhile
			}
		}
		if (whiteboard != null) {
			dto.replyToSubscriptions = whiteboard.toDTOs();
		}
	}

	// ------------------------------------------------------------------ services

	private final class Publisher implements MessagePublisher {

		@Override
		public Promise<Void> publish(Message message) {
			return instance.publish(message, null);
		}

		@Override
		public Promise<Void> publish(Message message, String channel) {
			requireNonNull(message, "message");
			MessageContext ctx = instance.newBuilder().withContext(message.getContext()).channel(channel).buildContext();
			return instance.publish(message, ctx);
		}

		@Override
		public Promise<Void> publish(Message message, MessageContext context) {
			return instance.publish(message, context);
		}

		@Override
		public Promise<Long> publish(PushStream<? extends Message> messages) {
			return instance.publish(messages, null);
		}

		@Override
		public Promise<Long> publish(PushStream<? extends Message> messages, MessageContext context) {
			return instance.publish(messages, context);
		}

		@Override
		public Promise<Void> publish(Function<MessageContextBuilder, Message> builderFunction) {
			try {
				return publish(builderFunction.apply(instance.newBuilder()));
			} catch (RuntimeException e) {
				return instance.promises().failed(e);
			}
		}

	}

	private final class Subscription implements MessageSubscription {

		@Override
		public PushStream<Message> subscribe(String channel) {
			return instance.subscribe(channel);
		}

		@Override
		public PushStream<Message> subscribe(MessageContext context) {
			return instance.subscribe(context);
		}

	}

	private final class BuilderFactory implements PrototypeServiceFactory<MessageContextBuilder> {

		@Override
		public MessageContextBuilder getService(Bundle bundle, ServiceRegistration<MessageContextBuilder> registration) {
			return instance.newBuilder();
		}

		@Override
		public void ungetService(Bundle bundle, ServiceRegistration<MessageContextBuilder> registration,
				MessageContextBuilder service) {
			// nothing to release
		}

	}

	private final class Runtime implements MessageServiceRuntime {

		@Override
		public MessagingRuntimeDTO getRuntimeDTO() {
			return instance.getRuntimeDTO();
		}

		@Override
		public ConnectionState getConnectionState() {
			return instance.getConnectionState();
		}

	}

}
