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

import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.messaging.api.FeatureNotSupportedException;
import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.MessageContextBuilder;
import org.gecko.messaging.api.MessagePublisher;
import org.gecko.messaging.api.MessageSubscription;
import org.gecko.messaging.api.MessagingException;
import org.gecko.messaging.api.cloudevents.CloudEvent;
import org.gecko.messaging.api.cloudevents.CloudEventConstants;
import org.gecko.messaging.api.cloudevents.CloudEventMessageContext;
import org.gecko.messaging.api.dto.MessagingRuntimeDTO;
import org.gecko.messaging.api.dto.SubscriptionDTO;
import org.gecko.messaging.api.replyto.ReplyToPublisher;
import org.gecko.messaging.api.runtime.ConnectionEvent;
import org.gecko.messaging.api.runtime.ConnectionListener;
import org.gecko.messaging.api.runtime.ConnectionState;
import org.gecko.messaging.api.runtime.MessageServiceRuntime;
import org.gecko.messaging.core.cloudevents.CloudEventCodec;
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
import org.osgi.util.promise.PromiseFactory;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushEventConsumer;
import org.osgi.util.pushstream.PushEventSource;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamProvider;
import org.osgi.util.pushstream.PushbackPolicyOption;
import org.osgi.util.pushstream.QueuePolicyOption;
import org.osgi.util.tracker.ServiceTracker;

/**
 * The protocol-neutral part of a messaging instance: one connection, owned and reconnected by the
 * instance, the subscriptions as state of the instance (restored after every reconnect),
 * connection events, acknowledgement, reply-to, the reply-to whiteboard, CloudEvents and the
 * service registrations.
 * <p>
 * A protocol implements the {@code do*} methods. Contract:
 * <ul>
 * <li>{@link #doConnect(long)} creates a fresh connection within the timeout; driver-side
 * automatic reconnect must be off — the instance is the only reconnect owner.</li>
 * <li>{@link #doDisconnect()} releases every resource of the connection; it is called before each
 * reconnect attempt and on stop, and must tolerate a broken or missing connection.</li>
 * <li>The protocol calls {@link #connectionLost(Throwable)} when the connection breaks.</li>
 * <li>{@link #doSubscribe(SubscriptionHandle)} makes one subscription on the broker; received
 * messages go to {@link SubscriptionHandle#deliver(SimpleMessageContext, byte[], Acknowledger)}.</li>
 * </ul>
 * Threads: one scheduler thread for connect and timeouts, plus {@code workerThreads} that deliver
 * messages and run handlers; all are released on {@link #stop()}.
 */
public abstract class AbstractMessagingInstance {

	private static final Logger LOG = Logger.getLogger(AbstractMessagingInstance.class.getName());

	/** The provider name of this implementation. */
	public static final String PROVIDER = "gecko";

	private final String protocol;
	protected final MessagingConfiguration config;
	protected final Features features;
	private final ScheduledThreadPoolExecutor scheduler;
	private final ThreadPoolExecutor workers;
	private final SerialExecutor events;
	protected final PromiseFactory promises;
	private final PushStreamProvider pushStreams = new PushStreamProvider();
	private final MessageContext defaultContext;

	private final Object stateLock = new Object();
	private volatile ConnectionState state = ConnectionState.CONNECTING;
	private boolean everConnected;
	private boolean stopped;
	private int attempt;
	private long reconnects;
	private ScheduledFuture<?> pendingConnect;

	private final Set<SubscriptionHandle> subscriptions = ConcurrentHashMap.newKeySet();
	private final List<ConnectionListener> listeners = new CopyOnWriteArrayList<>();

	private final ReplyToSupport replyTo;
	private ReplyToWhiteboard whiteboard;

	private BundleContext bundleContext;
	private final List<ServiceRegistration<?>> registrations = new ArrayList<>();
	private ServiceRegistration<MessageServiceRuntime> runtimeRegistration;
	private ServiceTracker<ConnectionListener, ConnectionListener> listenerTracker;

	/**
	 * Creates the instance.
	 *
	 * @param protocol the protocol name, e.g. {@code mqtt}
	 * @param config the configuration
	 * @param features the features this instance supports with this configuration
	 */
	protected AbstractMessagingInstance(String protocol, MessagingConfiguration config, Collection<String> features) {
		this.protocol = requireNonNull(protocol);
		this.config = requireNonNull(config);
		List<String> f = new ArrayList<>(features);
		if (config.cloudEvents() && !f.contains(FEATURE_CLOUD_EVENTS)) {
			f.add(FEATURE_CLOUD_EVENTS);
		}
		this.features = new Features(f);
		String name = config.name();
		this.scheduler = new ScheduledThreadPoolExecutor(1, threads("msg-" + name + "-scheduler"));
		this.scheduler.setRemoveOnCancelPolicy(true);
		this.scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		int n = config.workerThreads();
		this.workers = new ThreadPoolExecutor(n, n, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), threads("msg-" + name + "-worker"));
		this.workers.allowCoreThreadTimeOut(true);
		this.events = new SerialExecutor(workers);
		this.promises = new PromiseFactory(workers, scheduler);
		this.defaultContext = config.defaultContext();
		this.replyTo = new ReplyToSupport(this);
	}

	private static ThreadFactory threads(String prefix) {
		AtomicInteger count = new AtomicInteger();
		return r -> {
			Thread t = new Thread(r, prefix + "-" + count.incrementAndGet());
			t.setDaemon(true);
			return t;
		};
	}

	// ------------------------------------------------------------------ protocol SPI

	/**
	 * Creates a fresh connection.
	 *
	 * @param timeoutMillis the time the attempt may take, including the protocol handshake
	 * @return {@code true} if the broker resumed a previous session
	 * @throws Exception if the attempt failed or timed out
	 */
	protected abstract boolean doConnect(long timeoutMillis) throws Exception;

	/**
	 * Releases the connection and all its resources. Must not throw.
	 */
	protected abstract void doDisconnect();

	/**
	 * Publishes a message. Called only while connected.
	 *
	 * @param context the context to publish with; channel set, features checked; in binary
	 *            CloudEvents mode its headers already carry the attributes
	 * @param payload the payload
	 * @return a promise resolved when the message is handed over
	 * @throws Exception if publishing failed at once
	 */
	protected abstract Promise<Void> doPublish(SimpleMessageContext context, ByteBuffer payload) throws Exception;

	/**
	 * Makes the subscription on the broker. Called while connected, on subscribe and after every
	 * reconnect.
	 *
	 * @param handle the subscription
	 * @throws Exception if the broker refused it
	 */
	protected abstract void doSubscribe(SubscriptionHandle handle) throws Exception;

	/**
	 * Ends the subscription on the broker. Must not throw.
	 *
	 * @param handle the subscription
	 */
	protected abstract void doUnsubscribe(SubscriptionHandle handle);

	/**
	 * Returns whether a connect failure ends the reconnect cycle — only authentication and
	 * authorisation failures should.
	 *
	 * @param failure the failure
	 * @return {@code true} to give up
	 */
	protected boolean isFatal(Throwable failure) {
		return false;
	}

	/**
	 * Returns the header prefix of the protocol's CloudEvents binary binding.
	 *
	 * @return the prefix
	 * @throws FeatureNotSupportedException if the protocol has no binary mode
	 */
	protected String cloudEventHeaderPrefix() {
		throw new FeatureNotSupportedException(FEATURE_CLOUD_EVENTS, protocol + " has no CloudEvents binary mode");
	}

	/**
	 * Returns the default queue policy of subscription buffers.
	 *
	 * @return {@code BLOCK}, {@code DISCARD_OLDEST} or {@code FAIL}
	 */
	protected String defaultQueuePolicy() {
		return "BLOCK";
	}

	/**
	 * Returns the largest payload, {@code 0} if unlimited.
	 *
	 * @return the limit in bytes
	 */
	protected long maxMessageSize() {
		return 0;
	}

	/**
	 * Creates the name of a generated reply channel.
	 *
	 * @return the channel name
	 */
	protected String createReplyChannel() {
		return config.replyChannelPrefix("osgi/messaging/reply/" + config.clientId()) + "/" + UUID.randomUUID();
	}

	/**
	 * Returns the context to subscribe to a reply channel with.
	 *
	 * @param channel the reply channel
	 * @return the context
	 */
	protected SimpleMessageContext replySubscriptionContext(String channel) {
		return (SimpleMessageContext) new SimpleMessageContextBuilder().channel(channel).buildContext();
	}

	/**
	 * Prepares the builder of a response: channel and correlation id of the request. Protocols
	 * add their addressing, for example AMQP sends responses to a queue.
	 *
	 * @param builder the response builder
	 * @param request the request context
	 * @param replyChannel the channel to answer to
	 */
	protected void configureResponse(MessageContextBuilder builder, MessageContext request, String replyChannel) {
		builder.channel(replyChannel);
		if (request.getCorrelationId() != null) {
			builder.correlationId(request.getCorrelationId());
		}
	}

	// ------------------------------------------------------------------ lifecycle

	/**
	 * Registers the services and starts connecting.
	 *
	 * @param context the bundle context to register with
	 */
	public void start(BundleContext context) {
		this.bundleContext = context;
		Dictionary<String, Object> props = serviceProperties();
		MessagePublisher publisher = new Publisher();
		MessageSubscription subscription = new Subscription();
		registrations.add(context.registerService(MessagePublisher.class, publisher, props));
		registrations.add(context.registerService(MessageSubscription.class, subscription, props));
		registrations.add(context.registerService(MessageContextBuilder.class.getName(), new BuilderFactory(), props));
		if (features.has(FEATURE_REPLY_TO)) {
			registrations.add(context.registerService(ReplyToPublisher.class, replyTo, props));
		}
		runtimeRegistration = context.registerService(MessageServiceRuntime.class, new Runtime(), props);
		registrations.add(runtimeRegistration);
		listenerTracker = new ServiceTracker<>(context, ConnectionListener.class, null) {
			@Override
			public ConnectionListener addingService(ServiceReference<ConnectionListener> reference) {
				if (!targets(reference)) {
					return null;
				}
				ConnectionListener l = super.addingService(reference);
				if (l != null) {
					listeners.add(l);
					ConnectionEvent current = new ConnectionEvent(config.name(), null, state, false, false, System.currentTimeMillis(), null);
					events.execute(() -> notifyListener(l, current));
				}
				return l;
			}

			@Override
			public void removedService(ServiceReference<ConnectionListener> reference, ConnectionListener service) {
				listeners.remove(service);
				super.removedService(reference, service);
			}
		};
		listenerTracker.open();
		if (features.has(FEATURE_REPLY_TO_SUBSCRIPTION)) {
			whiteboard = new ReplyToWhiteboard(this, context);
			whiteboard.open();
		}
		synchronized (stateLock) {
			pendingConnect = scheduler.schedule(this::connectAttempt, 0, TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * Adds a listener directly, without the whiteboard. For use outside a framework.
	 *
	 * @param listener the listener; told the current state at once
	 */
	public void addConnectionListener(ConnectionListener listener) {
		listeners.add(listener);
		ConnectionEvent current = new ConnectionEvent(config.name(), null, state, false, false, System.currentTimeMillis(), null);
		events.execute(() -> notifyListener(listener, current));
	}

	/**
	 * Starts connecting without registering services. For use outside a framework.
	 */
	public void startStandalone() {
		synchronized (stateLock) {
			pendingConnect = scheduler.schedule(this::connectAttempt, 0, TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * Unregisters the services, ends all subscriptions, closes the connection and releases all
	 * threads.
	 */
	public void stop() {
		synchronized (stateLock) {
			if (stopped) {
				return;
			}
			stopped = true;
			if (pendingConnect != null) {
				pendingConnect.cancel(false);
			}
		}
		if (whiteboard != null) {
			whiteboard.close();
		}
		setState(ConnectionState.CLOSED, false, false, null);
		for (ServiceRegistration<?> r : registrations) {
			try {
				r.unregister();
			} catch (IllegalStateException e) {
				// already gone with the bundle
			}
		}
		registrations.clear();
		replyTo.close();
		for (SubscriptionHandle h : new ArrayList<>(subscriptions)) {
			closeSubscription(h, null);
		}
		try {
			doDisconnect();
		} catch (RuntimeException e) {
			LOG.log(Level.FINE, "Disconnect failed", e);
		}
		state = ConnectionState.CLOSED;
		if (listenerTracker != null) {
			listenerTracker.close();
		}
		scheduler.shutdownNow();
		workers.shutdown();
		try {
			if (!workers.awaitTermination(2, TimeUnit.SECONDS)) {
				workers.shutdownNow();
			}
		} catch (InterruptedException e) {
			workers.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	private Dictionary<String, Object> serviceProperties() {
		Hashtable<String, Object> props = new Hashtable<>();
		for (Map.Entry<String, Object> e : config.raw().entrySet()) {
			String k = e.getKey();
			if (k.startsWith(".") || k.startsWith("component.") || k.startsWith("service.") || k.equals("objectClass")
					|| k.toLowerCase().contains("password") || e.getValue() == null) {
				continue;
			}
			props.put(k, e.getValue());
		}
		props.put(MESSAGING_NAME_PROPERTY, config.name());
		props.put(MESSAGING_PROVIDER_PROPERTY, PROVIDER);
		props.put(MESSAGING_PROTOCOL_PROPERTY, new String[] { protocol });
		props.put(MESSAGING_FEATURE_PROPERTY, features.asSet().toArray(new String[0]));
		if (maxMessageSize() > 0) {
			props.put(MESSAGING_MAX_MESSAGE_SIZE_PROPERTY, maxMessageSize());
		}
		return props;
	}

	boolean targets(ServiceReference<?> reference) {
		Object target = reference.getProperty(MESSAGING_TARGET_PROPERTY);
		if (target == null) {
			return true;
		}
		return matches(target.toString());
	}

	boolean matches(String target) {
		try {
			Filter filter = FrameworkUtil.createFilter(target);
			ServiceRegistration<MessageServiceRuntime> r = runtimeRegistration;
			return r != null && filter.match(r.getReference());
		} catch (InvalidSyntaxException | IllegalStateException e) {
			LOG.log(Level.WARNING, "Invalid messaging target " + target, e);
			return false;
		}
	}

	// ------------------------------------------------------------------ connection

	private void connectAttempt() {
		synchronized (stateLock) {
			if (stopped) {
				return;
			}
			pendingConnect = null;
		}
		boolean sessionPresent;
		try {
			safeDisconnect();
			sessionPresent = doConnect(config.connectTimeout());
		} catch (Throwable t) {
			safeDisconnect();
			if (isFatal(t)) {
				LOG.log(Level.SEVERE, "Messaging instance " + config.name() + " gives up connecting", t);
				setState(ConnectionState.CLOSED, false, false, t);
				for (SubscriptionHandle h : new ArrayList<>(subscriptions)) {
					closeSubscription(h, t);
				}
				return;
			}
			LOG.log(Level.FINE, "Connect attempt of " + config.name() + " failed", t);
			scheduleReconnect(t);
			return;
		}
		boolean reconnect;
		synchronized (stateLock) {
			if (stopped) {
				safeDisconnect();
				return;
			}
			attempt = 0;
			reconnect = everConnected;
			everConnected = true;
			if (reconnect) {
				reconnects++;
			}
			state = ConnectionState.CONNECTED;
		}
		// restore before telling anyone: a listener that re-synchronises on a reconnect must
		// find the subscriptions in place
		for (SubscriptionHandle h : new ArrayList<>(subscriptions)) {
			if (h.isClosed()) {
				continue;
			}
			try {
				doSubscribe(h);
				h.setConnected(true);
			} catch (Exception e) {
				if (state != ConnectionState.CONNECTED) {
					return; // lost again, the next attempt restores
				}
				LOG.log(Level.WARNING, "Could not restore " + h, e);
				closeSubscription(h, e);
			}
		}
		fire(ConnectionState.CONNECTING, ConnectionState.CONNECTED, reconnect, sessionPresent, null);
	}

	private void safeDisconnect() {
		try {
			doDisconnect();
		} catch (RuntimeException e) {
			LOG.log(Level.FINE, "Disconnect failed", e);
		}
	}

	private void scheduleReconnect(Throwable cause) {
		synchronized (stateLock) {
			if (stopped || pendingConnect != null) {
				return;
			}
			long delay = Math.min(config.reconnectMaxDelay(), config.reconnectDelay() * (1L << Math.min(attempt, 20)));
			attempt++;
			pendingConnect = scheduler.schedule(this::connectAttempt, delay, TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * The protocol lost the connection. Starts the reconnect cycle.
	 *
	 * @param cause the cause, or {@code null}
	 */
	protected final void connectionLost(Throwable cause) {
		synchronized (stateLock) {
			if (stopped || state != ConnectionState.CONNECTED) {
				return;
			}
			state = ConnectionState.DISCONNECTED;
		}
		for (SubscriptionHandle h : subscriptions) {
			h.setConnected(false);
		}
		fire(ConnectionState.CONNECTED, ConnectionState.DISCONNECTED, false, false, cause);
		synchronized (stateLock) {
			attempt = 0;
		}
		scheduleReconnect(cause);
	}

	private void setState(ConnectionState newState, boolean reconnect, boolean sessionPresent, Throwable cause) {
		ConnectionState old;
		synchronized (stateLock) {
			old = state;
			if (old == newState) {
				return;
			}
			state = newState;
		}
		fire(old, newState, reconnect, sessionPresent, cause);
	}

	private void fire(ConnectionState previous, ConnectionState now, boolean reconnect, boolean sessionPresent, Throwable cause) {
		ConnectionEvent e = new ConnectionEvent(config.name(), previous, now, reconnect, sessionPresent, System.currentTimeMillis(), cause);
		LOG.fine(() -> "Messaging instance " + config.name() + ": " + previous + " -> " + now + (reconnect ? " (reconnect)" : ""));
		for (ConnectionListener l : listeners) {
			events.execute(() -> notifyListener(l, e));
		}
	}

	private static void notifyListener(ConnectionListener l, ConnectionEvent e) {
		try {
			l.connectionChanged(e);
		} catch (RuntimeException ex) {
			LOG.log(Level.WARNING, "A connection listener failed", ex);
		}
	}

	/** @return the current connection state */
	public ConnectionState getConnectionState() {
		return state;
	}

	/** @return the instance name */
	public String getName() {
		return config.name();
	}

	/** @return the features */
	public Features getFeatures() {
		return features;
	}

	/** @return the configuration */
	public MessagingConfiguration getConfiguration() {
		return config;
	}

	/** @return the promise factory of this instance */
	public PromiseFactory promises() {
		return promises;
	}

	/** @return the scheduler of this instance */
	protected ScheduledThreadPoolExecutor scheduler() {
		return scheduler;
	}

	/** @return the executor running delivery and handlers */
	protected ThreadPoolExecutor workers() {
		return workers;
	}

	/** @return a new builder of this instance */
	public MessageContextBuilder newBuilder() {
		return new SimpleMessageContextBuilder(features.asSet());
	}

	// ------------------------------------------------------------------ publish

	/**
	 * Publishes a message.
	 *
	 * @param message the message, only its payload is used
	 * @param context the context
	 * @return the promise of the hand-over
	 */
	public Promise<Void> publish(Message message, MessageContext context) {
		try {
			requireNonNull(message, "message");
			MessageContext ctx = context != null ? context : message.getContext();
			if (ctx == null) {
				ctx = defaultContext;
			}
			if (ctx == null || ctx.getChannel() == null) {
				ctx = mergeDefaultChannel(ctx);
			}
			features.checkPublish(ctx);
			ByteBuffer payload = message.payload();
			long max = maxMessageSize();
			if (max > 0 && payload.remaining() > max) {
				throw new MessagingException("The payload of " + payload.remaining() + " bytes exceeds the limit of " + max);
			}
			if (state != ConnectionState.CONNECTED) {
				throw new MessagingException("Messaging instance " + config.name() + " is not connected (" + state + ")");
			}
			SimpleMessageContext simple = SimpleMessageContextBuilder.toSimple(ctx);
			if (config.cloudEvents()) {
				return publishCloudEvent(simple, payload);
			}
			return doPublish(simple, payload);
		} catch (Exception e) {
			return promises.failed(e);
		}
	}

	private MessageContext mergeDefaultChannel(MessageContext ctx) {
		if (defaultContext == null || defaultContext.getChannel() == null) {
			throw new MessagingException("No channel given and none configured");
		}
		if (ctx == null) {
			return defaultContext;
		}
		return newBuilder().withContext(ctx).channel(defaultContext.getChannel(), defaultContext.getChannelExtension()).buildContext();
	}

	private Promise<Void> publishCloudEvent(SimpleMessageContext ctx, ByteBuffer payload) throws Exception {
		CloudEvent ce;
		if (ctx instanceof CloudEventMessageContext cem) {
			ce = cem.getCloudEvent();
		} else {
			ce = new SimpleCloudEvent(UUID.randomUUID().toString(), config.string("cloudEvents.source", "/osgi/messaging/" + config.name()),
					config.string("cloudEvents.type", "org.gecko.messaging.message"), null, null, OffsetDateTime.now(),
					ctx.getHeaders());
		}
		if (CloudEventConstants.MODE_STRUCTURED.equals(config.cloudEventsMode())) {
			byte[] envelope = CloudEventCodec.toStructured(ce, ctx, payload);
			SimpleMessageContextBuilder b = new SimpleMessageContextBuilder();
			b.withContext(ctx);
			b.contentType(CloudEventConstants.STRUCTURED_JSON_CONTENT_TYPE);
			b.contentEncoding(null);
			SimpleMessageContext wire = (SimpleMessageContext) b.buildContext();
			return doPublish(wire, ByteBuffer.wrap(envelope));
		}
		Map<String, Object> headers = CloudEventCodec.toBinaryHeaders(ce, cloudEventHeaderPrefix());
		SimpleMessageContextBuilder b = new SimpleMessageContextBuilder();
		MessageContext plain = new SimpleMessageContextBuilder().channel(ctx.getChannel(), ctx.getChannelExtension())
				.contentType(ctx.getContentType()).contentEncoding(ctx.getContentEncoding()).correlationId(ctx.getCorrelationId())
				.replyTo(ctx.getReplyToChannel()).extensions(ctx.getExtensions()).headers(headers).buildContext();
		b.withContext(plain);
		return doPublish((SimpleMessageContext) b.buildContext(), payload);
	}

	// ------------------------------------------------------------------ subscribe

	/**
	 * Subscribes.
	 *
	 * @param context the subscription context, {@code null} for the configured one
	 * @return the stream
	 */
	public PushStream<Message> subscribe(MessageContext context) {
		MessageContext ctx = context != null ? context : defaultContext;
		if (ctx == null || ctx.getChannel() == null) {
			ctx = mergeDefaultChannel(ctx);
		}
		features.checkSubscribe(ctx);
		SimpleMessageContext simple = SimpleMessageContextBuilder.toSimple(ctx);
		PushEventSource<Message> source = consumer -> open(simple, consumer);
		return pushStreams.buildStream(source)
				.withBuffer(new ArrayBlockingQueue<>(config.bufferSize()))
				.withQueuePolicy(QueuePolicyOption.valueOf(config.queuePolicy(defaultQueuePolicy())))
				.withPushbackPolicy(PushbackPolicyOption.FIXED, 0)
				.withExecutor(workers)
				.withScheduler(scheduler)
				.build();
	}

	/**
	 * Subscribes to a channel with the defaults.
	 *
	 * @param channel the channel
	 * @return the stream
	 */
	public PushStream<Message> subscribe(String channel) {
		return subscribe(new SimpleMessageContextBuilder().channel(requireNonNull(channel, "channel")).buildContext());
	}

	private AutoCloseable open(SimpleMessageContext ctx, PushEventConsumer<? super Message> consumer) throws Exception {
		SubscriptionHandle.Sink sink = new SubscriptionHandle.Sink() {
			@Override
			public long accept(Message message) throws Exception {
				return consumer.accept(PushEvent.data(message));
			}

			@Override
			public void closed(Throwable failure) {
				try {
					consumer.accept(failure == null ? PushEvent.close() : PushEvent.error(failure));
				} catch (Exception e) {
					LOG.log(Level.FINE, "Closing a stream failed", e);
				}
			}
		};
		SubscriptionHandle h = addSubscription(ctx, sink, false);
		return () -> closeSubscription(h, null);
	}

	/**
	 * Adds a subscription and makes it on the broker if connected.
	 *
	 * @param ctx the context
	 * @param sink the receiver
	 * @param internal whether it is an internal subscription
	 * @return the handle
	 * @throws Exception if the broker refused it
	 */
	SubscriptionHandle addSubscription(SimpleMessageContext ctx, SubscriptionHandle.Sink sink, boolean internal) throws Exception {
		if (stopped) {
			throw new MessagingException("Messaging instance " + config.name() + " is stopped");
		}
		SubscriptionHandle h = new SubscriptionHandle(this, ctx, sink, internal);
		subscriptions.add(h);
		if (state == ConnectionState.CONNECTED) {
			try {
				doSubscribe(h);
				h.setConnected(true);
			} catch (Exception e) {
				subscriptions.remove(h);
				h.markClosed();
				throw e;
			}
		}
		return h;
	}

	/**
	 * Ends a subscription.
	 *
	 * @param h the subscription
	 * @param failure the cause, {@code null} for a normal end
	 */
	void closeSubscription(SubscriptionHandle h, Throwable failure) {
		if (!h.markClosed()) {
			return;
		}
		subscriptions.remove(h);
		if (state == ConnectionState.CONNECTED) {
			try {
				doUnsubscribe(h);
			} catch (RuntimeException e) {
				LOG.log(Level.FINE, "Unsubscribe failed", e);
			}
		}
		for (AcknowledgeSupport open : new ArrayList<>(h.openAcknowledgements)) {
			open.reject();
		}
		h.sink().closed(failure);
	}

	void deliver(SubscriptionHandle h, SimpleMessageContext messageContext, byte[] payload, Acknowledger acknowledger) {
		SimpleMessage message;
		try {
			message = decode(messageContext, payload);
		} catch (RuntimeException | StackOverflowError e) {
			// remote input: no stack trace per message at WARNING, it would flood the log
			LOG.warning(() -> "Dropping an undecodable message on " + h + ": " + e.getClass().getSimpleName());
			LOG.log(Level.FINE, "Undecodable message", e);
			if (acknowledger != null) {
				acknowledger.reject(false);
			}
			return;
		}
		SimpleMessageContext sub = h.context();
		AcknowledgeSupport ack = null;
		if (acknowledger != null || sub.getPostAcknowledge() != null || sub.getPostReject() != null || h.needsAcknowledger()) {
			AcknowledgeSupport[] self = new AcknowledgeSupport[1];
			ack = new AcknowledgeSupport(acknowledger, sub.isRequeueOnReject(), sub.getPostAcknowledge(), sub.getPostReject(),
					() -> h.openAcknowledgements.remove(self[0]));
			self[0] = ack;
			message = message.withContext(((SimpleMessageContext) message.getContext()).withAcknowledgeHandler(ack));
			ack.setMessage(message);
		}
		if (ack != null) {
			try {
				if (sub.getAcknowledgeFunction() != null) {
					sub.getAcknowledgeFunction().accept(message);
					ack.acknowledge(); // undecided counts as accepted
					if (ack.getAcknowledgeState() != org.gecko.messaging.api.acknowledge.AcknowledgeType.ACKNOWLEDGED) {
						return;
					}
				} else if (sub.getAcknowledgeFilter() != null) {
					if (sub.getAcknowledgeFilter().test(message)) {
						ack.acknowledge();
					} else {
						ack.reject();
						return;
					}
				} else if (sub.isManualAcknowledge()) {
					h.openAcknowledgements.add(ack);
				} else {
					ack.acknowledge();
				}
			} catch (RuntimeException e) {
				LOG.log(Level.WARNING, "Acknowledgement decision failed on " + h + ", rejecting", e);
				ack.reject();
				return;
			}
		}
		try {
			long r;
			synchronized (h) {
				r = h.sink().accept(message);
			}
			if (r < 0) {
				closeSubscription(h, null);
			}
		} catch (Exception e) {
			closeSubscription(h, e);
		}
	}

	private SimpleMessage decode(SimpleMessageContext ctx, byte[] payload) {
		if (!config.cloudEvents()) {
			return SimpleMessage.ofOwned(payload, ctx);
		}
		if (CloudEventConstants.MODE_STRUCTURED.equals(config.cloudEventsMode())) {
			String ct = ctx.getContentType();
			boolean envelope = ct == null ? looksLikeJsonObject(payload)
					: ct.toLowerCase().startsWith(CloudEventConstants.STRUCTURED_JSON_CONTENT_TYPE);
			if (!envelope) {
				return SimpleMessage.ofOwned(payload, ctx);
			}
			CloudEventCodec.Structured s = CloudEventCodec.fromStructured(payload);
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
		CloudEventCodec.Binary bin = CloudEventCodec.fromBinaryHeaders(ctx.getHeaders(), cloudEventHeaderPrefix());
		if (bin.cloudEvent() == null) {
			return SimpleMessage.ofOwned(payload, ctx);
		}
		SimpleMessageContextBuilder b = new SimpleMessageContextBuilder();
		b.withContext(ctx);
		ctx.getHeaders().keySet().forEach(k -> b.header(k, null));
		b.headers(bin.otherHeaders());
		return SimpleMessage.ofOwned(payload, SimpleMessageContextBuilder.cloudEventContext(b.buildContext(), bin.cloudEvent()));
	}

	private static boolean looksLikeJsonObject(byte[] payload) {
		for (byte b : payload) {
			if (!Character.isWhitespace(b)) {
				return b == '{';
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ runtime

	/**
	 * Returns the runtime DTO.
	 *
	 * @return the DTO
	 */
	public MessagingRuntimeDTO getRuntimeDTO() {
		MessagingRuntimeDTO dto = new MessagingRuntimeDTO();
		ServiceRegistration<MessageServiceRuntime> r = runtimeRegistration;
		if (r != null) {
			try {
				dto.serviceDTO = r.getReference().adapt(ServiceReferenceDTO.class);
			} catch (IllegalStateException e) {
				// unregistered meanwhile
			}
		}
		dto.name = config.name();
		dto.providerName = PROVIDER;
		dto.protocols = new String[] { protocol };
		dto.features = features.asSet().toArray(new String[0]);
		dto.connectionURI = config.displayUri();
		dto.connectionState = state.name();
		synchronized (stateLock) {
			dto.reconnects = reconnects;
		}
		dto.subscriptions = subscriptions.stream().filter(h -> !h.isInternal()).map(SubscriptionHandle::toDTO)
				.toArray(SubscriptionDTO[]::new);
		dto.replyToSubscriptions = whiteboard == null ? new org.gecko.messaging.api.dto.ReplyToSubscriptionDTO[0] : whiteboard.toDTOs();
		return dto;
	}

	BundleContext bundleContext() {
		return bundleContext;
	}

	MessageContext defaultContext() {
		return defaultContext;
	}

	// ------------------------------------------------------------------ services

	private final class Publisher implements MessagePublisher {

		@Override
		public Promise<Void> publish(Message message) {
			return AbstractMessagingInstance.this.publish(message, null);
		}

		@Override
		public Promise<Void> publish(Message message, String channel) {
			requireNonNull(message, "message");
			MessageContext ctx = newBuilder().withContext(message.getContext()).channel(channel).buildContext();
			return AbstractMessagingInstance.this.publish(message, ctx);
		}

		@Override
		public Promise<Void> publish(Message message, MessageContext context) {
			return AbstractMessagingInstance.this.publish(message, context);
		}

		@Override
		public Promise<Void> publish(Function<MessageContextBuilder, Message> builderFunction) {
			try {
				return publish(builderFunction.apply(newBuilder()));
			} catch (RuntimeException e) {
				return promises.failed(e);
			}
		}

	}

	private final class Subscription implements MessageSubscription {

		@Override
		public PushStream<Message> subscribe(String channel) {
			return AbstractMessagingInstance.this.subscribe(channel);
		}

		@Override
		public PushStream<Message> subscribe(MessageContext context) {
			return AbstractMessagingInstance.this.subscribe(context);
		}

	}

	private final class BuilderFactory implements PrototypeServiceFactory<MessageContextBuilder> {

		@Override
		public MessageContextBuilder getService(Bundle bundle, ServiceRegistration<MessageContextBuilder> registration) {
			return newBuilder();
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
			return AbstractMessagingInstance.this.getRuntimeDTO();
		}

		@Override
		public ConnectionState getConnectionState() {
			return state;
		}

	}

	/** @return the reply-to support of this instance */
	public ReplyToPublisher replyToPublisher() {
		return replyTo;
	}

}
