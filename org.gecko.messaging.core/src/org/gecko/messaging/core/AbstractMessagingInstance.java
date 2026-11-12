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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.messaging.api.FeatureNotSupportedException;
import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.MessageContextBuilder;
import org.gecko.messaging.api.MessagingException;
import org.gecko.messaging.api.cloudevents.CloudEventConstants;
import org.gecko.messaging.api.dto.MessagingRuntimeDTO;
import org.gecko.messaging.api.dto.ReplyToSubscriptionDTO;
import org.gecko.messaging.api.dto.SubscriptionDTO;
import org.gecko.messaging.api.replyto.ReplyToPublisher;
import org.gecko.messaging.api.runtime.ConnectionEvent;
import org.gecko.messaging.api.runtime.ConnectionListener;
import org.gecko.messaging.api.runtime.ConnectionState;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.util.promise.Promise;
import org.osgi.util.promise.PromiseFactory;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushEventConsumer;
import org.osgi.util.pushstream.PushEventSource;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamProvider;
import org.osgi.util.pushstream.PushbackPolicyOption;

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
 * Threads: one scheduler thread for connect and timeouts, {@code workerThreads} that deliver
 * messages and run handlers, and one thread, started on demand, for promise callbacks and
 * connection events; all are released on {@link #stop()}.
 */
public abstract class AbstractMessagingInstance {

	private static final Logger LOG = Logger.getLogger(AbstractMessagingInstance.class.getName());

	/** Shortest wait between connect attempts, ms: a zero delay must not make a busy loop. */
	static final long MIN_RECONNECT_DELAY = 10;

	/**
	 * What the structured CloudEvents envelope carries for any protocol: content type, headers as
	 * extensions, correlation and reply-to, and with them reply-to as a whole.
	 */
	static final List<String> STRUCTURED_ENVELOPE_FEATURES = List.of(FEATURE_CONTENT_TYPE, FEATURE_HEADERS,
			FEATURE_REPLY_TO, FEATURE_REPLY_TO_MANY, FEATURE_REPLY_TO_SUBSCRIPTION, FEATURE_GENERATE_CORRELATION_ID,
			FEATURE_GENERATE_REPLY_CHANNEL);

	/** The provider name of this implementation. */
	public static final String PROVIDER = "gecko";

	private final String protocol;
	protected final MessagingConfiguration config;
	/** The TLS scheme and stores of the connection, read when the instance is created. */
	protected final TlsSettings tls;
	protected final Features features;
	private final ScheduledThreadPoolExecutor scheduler;
	private final ExecutorService workers;
	/**
	 * Promise callbacks and connection events: one thread, started on demand, apart from the
	 * workers, so that handlers blocking the workers never hold back what releases them.
	 */
	private final ThreadPoolExecutor callbacks;
	private final SerialExecutor events;
	protected final PromiseFactory promises;
	private final PushStreamProvider pushStreams = new PushStreamProvider();
	private final MessageContext defaultContext;
	/** How messages go on the wire, chosen in {@link #prepare()}. */
	private volatile CloudEventBinding cloudEvents = CloudEventBinding.NONE;
	/** The send window of the stream publishes, see {@link #acquireSend()}. */
	private final Object sendLock = new Object();
	/** Guarded by sendLock. */
	private int streamOutstanding;
	private final AtomicLong streamPublishFailures = new AtomicLong();
	/** Single publishes waiting for the connection, {@code publishAwaitConnection}. */
	private final PublishWait publishWait;
	/** Stream publishes waiting for the connection wait on it, {@code streamPublishAwaitConnection}. */
	private final Object connectedSignal = new Object();
	private final CloudEventBinding.Wire wire = this::doPublish;

	private final Object stateLock = new Object();
	private volatile ConnectionState state = ConnectionState.CONNECTING;
	/**
	 * Connected and the subscriptions restored: what waited for the connection may go out. Set
	 * after the restore, cleared whenever the state leaves {@code CONNECTED}.
	 */
	private volatile boolean ready;
	private boolean everConnected;
	private boolean stopped;
	private int attempt;
	/** When the current connection was made, {@link System#nanoTime()}; under stateLock. */
	private long connectedAt;
	private long reconnects;
	private ScheduledFuture<?> pendingConnect;

	private final Set<SubscriptionHandle> subscriptions = ConcurrentHashMap.newKeySet();
	private final List<ConnectionListener> listeners = new CopyOnWriteArrayList<>();

	private final ReplyToSupport replyTo;
	/** The services of the instance, {@code null} standalone. */
	private volatile OsgiBinding osgi;

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
		// before any thread exists: a store that cannot be read fails the instance here
		this.tls = TlsSettings.of(config);
		Set<String> f = new LinkedHashSet<>(features);
		if (config.cloudEvents()) {
			f.add(FEATURE_CLOUD_EVENTS);
		}
		if (CloudEventConstants.MODE_STRUCTURED.equals(config.cloudEventsMode())) {
			// the envelope carries these whatever the protocol has itself
			f.addAll(STRUCTURED_ENVELOPE_FEATURES);
		}
		if (f.containsAll(List.of(FEATURE_REPLY_TO, FEATURE_REPLY_TO_SUBSCRIPTION, FEATURE_HEADERS, FEATURE_GENERATE_REPLY_CHANNEL))) {
			// sessions are built of these: generated channels, correlation and control headers
			f.add(FEATURE_REPLY_TO_SESSION);
		}
		this.features = new Features(f);
		String name = config.name();
		this.scheduler = new ScheduledThreadPoolExecutor(1, threads("msg-" + name + "-scheduler"));
		this.scheduler.setRemoveOnCancelPolicy(true);
		this.scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		if (config.virtualWorkers()) {
			// a virtual thread per task: a handler that blocks holds back no other; the buffers and
			// the in-flight limits of the handlers bound the tasks
			this.workers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("msg-" + name + "-worker-", 1).factory());
		} else {
			int n = config.workerThreads();
			ThreadPoolExecutor pool = new ThreadPoolExecutor(n, n, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
					threads("msg-" + name + "-worker"));
			pool.allowCoreThreadTimeOut(true);
			this.workers = pool;
		}
		this.callbacks = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
				threads("msg-" + name + "-callbacks"));
		this.callbacks.allowCoreThreadTimeOut(true);
		this.events = new SerialExecutor(callbacks);
		this.promises = new PromiseFactory(callbacks, scheduler);
		this.defaultContext = config.defaultContext();
		this.replyTo = new ReplyToSupport(this);
		this.publishWait = new PublishWait(this, config);
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
	 * Releases what the protocol holds beyond a connection, such as a client shared by all
	 * connections. Called once on {@link #stop()}, after the last {@link #doDisconnect()}. Must
	 * not throw.
	 */
	protected void doClose() {
		// nothing beyond the connection by default
	}

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

	/** Schemes that carry credentials in clear text. */
	private static final Set<String> PLAINTEXT_SCHEMES = Set.of("tcp", "mqtt", "ws", "amqp");

	/** Credentials over a plaintext scheme are allowed, but said once. */
	private void warnPlaintextCredentials() {
		String uri = config.uri();
		int colon = uri == null ? -1 : uri.indexOf("://");
		boolean credentials = config.username() != null || config.password() != null
				|| (colon > 0 && config.displayUri().length() < uri.length());
		if (credentials && colon > 0 && PLAINTEXT_SCHEMES.contains(uri.substring(0, colon).toLowerCase(Locale.ROOT))) {
			LOG.warning(() -> "Messaging instance " + config.name() + " sends credentials unencrypted to "
					+ config.displayUri() + "; use a TLS scheme");
		}
	}

	/**
	 * Chooses the CloudEvents binding once, before the instance announces anything; refuses the
	 * binary mode on a protocol without headers, otherwise every message would fail.
	 */
	private void prepare() {
		cloudEvents = CloudEventBinding.of(config, this::cloudEventHeaderPrefix);
		warnPlaintextCredentials();
	}

	/**
	 * Checks what the protocol cannot do beyond the announced features, such as a quality of
	 * service above its maximum, before anything is sent or subscribed. Called after the feature
	 * check, for every publish and subscribe.
	 *
	 * @param context the context of the publish or subscription
	 * @param publish {@code true} for a publish, {@code false} for a subscription
	 * @throws FeatureNotSupportedException if the protocol cannot do what the context asks
	 */
	protected void checkContext(MessageContext context, boolean publish) {
		// nothing beyond the features by default
	}

	/**
	 * Returns whether the protocol creates every channel on first use, so that it cannot keep to
	 * {@code autoCreate=false} ("do not create, fail if missing"). Such an instance refuses an
	 * explicit {@code autoCreate=false} instead of ignoring it.
	 *
	 * @return {@code true} for protocols without channel management, such as MQTT
	 */
	protected boolean createsChannelsImplicitly() {
		return false;
	}

	void checkProtocol(MessageContext context, boolean publish) {
		if (createsChannelsImplicitly()
				&& Boolean.FALSE.equals(Extensions.bool(context.getExtensions().get(EXTENSION_AUTO_CREATE)))) {
			throw new FeatureNotSupportedException(FEATURE_AUTO_CREATE,
					protocol + " creates channels on first use and cannot refuse to create one");
		}
		checkContext(context, publish);
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
	 * Returns the receive window the client sets: the unacknowledged messages the broker sends at
	 * most, per connection (MQTT 5 {@code receiveMaximum}) or per subscription without buffer of
	 * its own (AMQP {@code prefetch}). Bindings compute it with {@link #receiveWindow(String, int)}.
	 *
	 * @return the window, {@code 0} if the client sets none
	 */
	protected int receiveWindow() {
		return 0;
	}

	/**
	 * Returns whether the protocol sets the receive window per subscription, so that it follows the
	 * buffer of each subscription; otherwise it is one for the connection.
	 *
	 * @return {@code true} for a window per subscription
	 */
	protected boolean receiveWindowPerSubscription() {
		return false;
	}

	/**
	 * Returns a configured receive window, lowered to {@code bufferSize}: the broker then stops
	 * sending before a buffer fills, and a full {@code BLOCK} buffer never holds the protocol's
	 * thread (§8). Logged once when lowered. Bytes are not adjusted for: when the window of
	 * messages of the largest size exceeds {@code bufferBytes}, that is logged once, as a warning
	 * if one of the values is configured.
	 *
	 * @param key the configuration key of the window
	 * @param defaultValue its default
	 * @return the window to set
	 */
	protected final int receiveWindow(String key, int defaultValue) {
		long configured = Math.max(1, config.number(key, defaultValue));
		int window = (int) Math.min(configured, config.bufferSize());
		if (window < configured) {
			LOG.info(() -> config.name() + ": " + key + " " + configured + " lowered to bufferSize " + window
					+ ", so that the broker stops sending before a buffer is full");
		}
		long max = maxMessageSize();
		if (max > 0 && window * max > config.bufferBytes()) {
			Map<String, Object> raw = config.raw();
			boolean chosen = raw.containsKey(key) || raw.containsKey("bufferBytes") || raw.containsKey("maxMessageSize");
			LOG.log(chosen ? Level.WARNING : Level.FINE, () -> config.name() + ": " + key + " " + window + " messages of up to "
					+ max + " bytes exceed bufferBytes " + config.bufferBytes()
					+ ": large messages can fill a buffer before the broker stops sending, the queue policy then applies");
		}
		return window;
	}

	/**
	 * Returns the receive window that applies to a subscription.
	 *
	 * @param h the subscription
	 * @return the window, {@code 0} if the client sets none
	 */
	protected final int receiveWindow(SubscriptionHandle h) {
		int window = receiveWindow();
		SubscriptionBuffer buffer = h.sink().buffer();
		return window > 0 && receiveWindowPerSubscription() && buffer != null ? Math.min(window, buffer.size()) : window;
	}

	/**
	 * Warns when a buffer is smaller than the receive window that applies to it and would block
	 * or fail when full: the window cannot follow it (one per connection, or a shared reply
	 * channel).
	 *
	 * @param buffer the buffer
	 * @param window the window that applies
	 * @param what what the buffer belongs to
	 * @param level the level of the message
	 */
	void checkWindow(SubscriptionBuffer buffer, int window, String what, Level level) {
		if (window > buffer.size() && !buffer.dropsWhenFull()) {
			LOG.log(level, () -> config.name() + ": the buffer of " + what + " (" + buffer.size()
					+ ") is smaller than the receive window (" + window + "): when it is full, it holds up delivery of the connection");
		}
	}

	/**
	 * Creates the name of a generated reply channel.
	 *
	 * @return the channel name
	 */
	protected String createReplyChannel() {
		return config.replyChannelPrefix(DEFAULT_REPLY_CHANNEL_PREFIX + config.clientId()) + "/" + UUID.randomUUID();
	}

	/**
	 * Returns the context to subscribe to a reply channel with.
	 *
	 * @param channel the reply channel
	 * @return the context
	 */
	protected SimpleMessageContext replySubscriptionContext(String channel) {
		return new SimpleMessageContextBuilder().channel(channel).buildSimple();
	}

	/**
	 * Prepares the builder of a response: channel and correlation id of the request. A protocol
	 * may add its own addressing; all bindings here answer on the reply channel as it is.
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
		prepare();
		OsgiBinding binding = new OsgiBinding(this, context);
		osgi = binding;
		binding.open();
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
	 * Removes a listener.
	 *
	 * @param listener the listener
	 */
	public void removeConnectionListener(ConnectionListener listener) {
		listeners.remove(listener);
	}

	/**
	 * Starts connecting without registering services. For use outside a framework.
	 */
	public void startStandalone() {
		prepare();
		synchronized (stateLock) {
			pendingConnect = scheduler.schedule(this::connectAttempt, 0, TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * Unregisters the services, ends all subscriptions, closes the connection and releases all
	 * threads.
	 */
	public final void stop() {
		synchronized (stateLock) {
			if (stopped) {
				return;
			}
			stopped = true;
			if (pendingConnect != null) {
				pendingConnect.cancel(false);
			}
		}
		OsgiBinding binding = osgi;
		setState(ConnectionState.CLOSED, false, false, null);
		publishWait.failAll("is stopped");
		signalConnected();
		if (binding != null) {
			binding.unregister();
		}
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
		try {
			doClose();
		} catch (RuntimeException e) {
			LOG.log(Level.FINE, "Closing the protocol failed", e);
		}
		if (binding != null) {
			binding.closeListeners();
		}
		scheduler.shutdownNow();
		shutdown(workers);
		shutdown(callbacks);
	}

	private static void shutdown(ExecutorService executor) {
		executor.shutdown();
		try {
			if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
				executor.shutdownNow();
			}
		} catch (InterruptedException e) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * @param reference a service that may name a messaging target
	 * @return {@code true} if it has no target or its target matches this instance
	 */
	boolean targets(ServiceReference<?> reference) {
		OsgiBinding binding = osgi;
		return binding == null || binding.targets(reference);
	}

	/** @return the protocol name */
	String protocol() {
		return protocol;
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
				publishWait.failAll("gave up connecting: " + t);
				signalConnected();
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
		ConnectionState previous;
		synchronized (stateLock) {
			if (stopped) {
				safeDisconnect();
				return;
			}
			connectedAt = System.nanoTime();
			previous = state;
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
		synchronized (stateLock) {
			ready = state == ConnectionState.CONNECTED;
		}
		signalConnected();
		// what waited for the connection goes out before anything published later
		publishWait.send();
		// CONNECTING on the first connect, DISCONNECTED on a reconnect
		fire(previous, ConnectionState.CONNECTED, reconnect, sessionPresent, null);
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
			long max = Math.max(MIN_RECONNECT_DELAY, config.reconnectMaxDelay());
			long backoff = Math.min(max, Math.max(MIN_RECONNECT_DELAY, config.reconnectDelay()) * (1L << Math.min(attempt, 20)));
			// between half and all of the backoff: a fleet does not reconnect in lockstep after a broker restart
			long delay = backoff / 2 + ThreadLocalRandom.current().nextLong(backoff / 2 + 1);
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
			ready = false;
		}
		for (SubscriptionHandle h : subscriptions) {
			h.setConnected(false);
		}
		fire(ConnectionState.CONNECTED, ConnectionState.DISCONNECTED, false, false, cause);
		synchronized (stateLock) {
			// the backoff starts over only after a connection that held; one that drops at once
			// (a client id taken over, a broker refusing after the handshake) keeps backing off
			if (System.nanoTime() - connectedAt >= TimeUnit.MILLISECONDS.toNanos(config.reconnectMaxDelay())) {
				attempt = 0;
			}
		}
		scheduleReconnect(cause);
	}

	/** Wakes the stream publishes waiting for the connection. */
	private void signalConnected() {
		synchronized (connectedSignal) {
			connectedSignal.notifyAll();
		}
	}

	/**
	 * Waits until the instance is connected ({@code streamPublishAwaitConnection}).
	 *
	 * @return {@code false} if the instance is closed and never connects again
	 * @throws InterruptedException if interrupted
	 */
	boolean awaitConnected() throws InterruptedException {
		synchronized (connectedSignal) {
			while (!ready) {
				if (state == ConnectionState.CLOSED || isStopped()) {
					return false;
				}
				connectedSignal.wait(100);
			}
			return true;
		}
	}

	/** @return whether the instance is connected and its subscriptions are restored */
	boolean isConnected() {
		return ready;
	}

	private void setState(ConnectionState newState, boolean reconnect, boolean sessionPresent, Throwable cause) {
		ConnectionState old;
		synchronized (stateLock) {
			old = state;
			if (old == newState) {
				return;
			}
			state = newState;
			ready = false;
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
	protected ExecutorService workers() {
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
			checkProtocol(ctx, true);
			ByteBuffer payload = message.payload();
			long max = maxMessageSize();
			if (max > 0 && payload.remaining() > max) {
				throw new MessagingException("The payload of " + payload.remaining() + " bytes exceeds the limit of " + max);
			}
			SimpleMessageContext simple = SimpleMessageContextBuilder.toSimple(ctx);
			// waiting is ready-based: a message must not overtake the restore of the subscriptions
			if (state != ConnectionState.CONNECTED || publishWait.enabled() && (!ready || publishWait.hasWaiting())) {
				// a message published later never overtakes one waiting for the connection
				if (publishWait.enabled() && state != ConnectionState.CLOSED) {
					return publishWait.offer(simple, payload);
				}
				if (state != ConnectionState.CONNECTED) {
					throw new MessagingException("Messaging instance " + config.name() + " is not connected (" + state + ")");
				}
			}
			return send(simple, payload);
		} catch (Exception e) {
			return promises.failed(e);
		}
	}

	/**
	 * Encodes and hands a checked message to the protocol.
	 *
	 * @param context the context, checked
	 * @param payload the payload
	 * @return the promise of the hand-over
	 */
	Promise<Void> send(SimpleMessageContext context, ByteBuffer payload) {
		try {
			return cloudEvents.publish(context, payload, wire);
		} catch (Exception e) {
			return promises.failed(e);
		}
	}

	/**
	 * Publishes every message of a stream in its order, with at most {@link #publishWindow()}
	 * messages not yet handed over.
	 *
	 * @param messages the messages
	 * @param context the context of every message, {@code null} for the context of each message
	 * @return the promise of the number of messages handed over
	 */
	public Promise<Long> publish(PushStream<? extends Message> messages, MessageContext context) {
		requireNonNull(messages, "messages");
		if (!List.of("stop", "continue").contains(streamFailures())) {
			messages.close();
			return promises.failed(new MessagingException("streamPublishFailures must be stop or continue, not " + streamFailures()));
		}
		StreamPublish publish = new StreamPublish(this, context, streamFailures(),
				config.bool("streamPublishAwaitConnection", false), promises.deferred());
		messages.forEachEvent(publish);
		return publish.promise();
	}

	/**
	 * Returns the messages the stream publishes of this instance may have outstanding together:
	 * the protocol's own limit ({@link #protocolSendWindow()}), at most {@code publishWindow}
	 * (default 100). Asked for every message, so that a new limit after a reconnect applies.
	 *
	 * @return the window
	 */
	final int publishWindow() {
		return (int) Math.max(1, Math.min(config.number("publishWindow", 100), protocolSendWindow()));
	}

	/**
	 * Returns how many messages the protocol takes not yet handed over before it refuses more:
	 * MQTT 5 the broker's receive maximum, MQTT 3.1.1 {@code maxInflight}.
	 *
	 * @return the limit, {@link Integer#MAX_VALUE} for none
	 */
	protected int protocolSendWindow() {
		return Integer.MAX_VALUE;
	}

	/**
	 * Waits for room in the send window shared by the stream publishes of the instance.
	 *
	 * @return {@code false} if the instance stopped meanwhile
	 * @throws InterruptedException if interrupted
	 */
	boolean acquireSend() throws InterruptedException {
		synchronized (sendLock) {
			while (streamOutstanding >= publishWindow()) {
				if (isStopped()) {
					return false;
				}
				sendLock.wait(100);
			}
			streamOutstanding++;
			return true;
		}
	}

	/**
	 * Takes room in the send window without waiting.
	 *
	 * @return {@code false} if the window is full
	 */
	boolean tryAcquireSend() {
		synchronized (sendLock) {
			if (streamOutstanding >= publishWindow()) {
				return false;
			}
			streamOutstanding++;
			return true;
		}
	}

	/** A message of a stream publish, or one that waited for the connection, was handed over or failed. */
	void releaseSend() {
		synchronized (sendLock) {
			streamOutstanding--;
			sendLock.notifyAll();
		}
		if (publishWait.enabled() && publishWait.hasWaiting()) {
			publishWait.send();
		}
	}

	/** A message of a stream publish failed and was skipped. */
	void streamPublishFailed() {
		streamPublishFailures.incrementAndGet();
	}

	/** @return {@code stop} or {@code continue}: what a failed message of a stream publish does */
	private String streamFailures() {
		return config.string("streamPublishFailures", "stop").trim().toLowerCase(Locale.ROOT);
	}

	/** @return whether the instance is stopped */
	boolean isStopped() {
		synchronized (stateLock) {
			return stopped;
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
		checkProtocol(ctx, false);
		return subscribe(SimpleMessageContextBuilder.toSimple(ctx), null, null);
	}

	/**
	 * Builds a buffered stream of a source of the instance, with the buffer settings of a context.
	 *
	 * @param source the source
	 * @param context the context with buffer extensions
	 * @return the stream
	 */
	PushStream<Message> stream(PushEventSource<Message> source, MessageContext context) {
		return stream(source, buffer(context));
	}

	/**
	 * Returns a buffer with the settings of a context, bounded by the instance's.
	 *
	 * @param context the context with buffer extensions, {@code null} for the instance's settings
	 * @return the buffer
	 * @throws MessagingException if a setting is invalid
	 */
	SubscriptionBuffer buffer(MessageContext context) {
		return SubscriptionBuffer.of(config, defaultQueuePolicy(),
				context != null ? context : new SimpleMessageContextBuilder().buildSimple());
	}

	/**
	 * Builds a stream of a source of the instance through a buffer.
	 *
	 * @param source the source
	 * @param buffer the buffer, used by this stream only
	 * @return the stream
	 */
	PushStream<Message> stream(PushEventSource<Message> source, SubscriptionBuffer buffer) {
		return pushStreams.buildStream(source)
				.withBuffer(buffer.newQueue())
				.withQueuePolicy(buffer)
				.withPushbackPolicy(PushbackPolicyOption.FIXED, 0)
				.withExecutor(workers)
				.withScheduler(scheduler)
				.build()
				.filter(buffer::taken);
	}

	/**
	 * Takes a message before it enters the buffer of a subscription stream.
	 */
	@FunctionalInterface
	interface Intercept {

		/**
		 * @param message the message
		 * @param handle the subscription
		 * @return {@code true} to deliver it to the stream, {@code false} if it is consumed
		 */
		boolean deliver(Message message, SubscriptionHandle handle);

	}

	/**
	 * Subscribes a stream, features checked.
	 *
	 * @param simple the context
	 * @param intercept takes messages before the buffer, {@code null} for none
	 * @param opened told the subscription once it is made, {@code null} for none
	 * @return the stream
	 */
	PushStream<Message> subscribe(SimpleMessageContext simple, Intercept intercept, Consumer<SubscriptionHandle> opened) {
		SubscriptionBuffer buffer = SubscriptionBuffer.of(config, defaultQueuePolicy(), simple);
		PushEventSource<Message> source = consumer -> open(simple, consumer, buffer, intercept, opened);
		return pushStreams.buildStream(source)
				.withBuffer(buffer.newQueue())
				.withQueuePolicy(buffer)
				.withPushbackPolicy(PushbackPolicyOption.FIXED, 0)
				.withExecutor(workers)
				.withScheduler(scheduler)
				.build()
				.filter(buffer::taken);
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

	private AutoCloseable open(SimpleMessageContext ctx, PushEventConsumer<? super Message> consumer,
			SubscriptionBuffer buffer, Intercept intercept, Consumer<SubscriptionHandle> opened) throws Exception {
		SubscriptionHandle[] self = new SubscriptionHandle[1];
		SubscriptionHandle.Sink sink = new SubscriptionHandle.Sink() {
			@Override
			public long accept(Message message) throws Exception {
				if (intercept != null && !intercept.deliver(message, self[0])) {
					// consumed before the buffer: taken as far as the broker is concerned
					AcknowledgeSupport ack = AcknowledgeSupport.of(message);
					if (ack != null) {
						ack.taken();
					}
					return 0;
				}
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

			@Override
			public SubscriptionBuffer buffer() {
				return buffer;
			}
		};
		SubscriptionHandle h = addSubscription(ctx, sink, false);
		self[0] = h;
		buffer.attach(h);
		checkWindow(buffer, receiveWindow(h), h.toString(), Level.WARNING);
		if (opened != null) {
			opened.accept(h);
		}
		return () -> {
			// first, so that a delivery blocked on the full buffer returns
			buffer.release();
			closeSubscription(h, null);
		};
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
			open.abandoned();
		}
		h.sink().closed(failure);
	}

	void deliver(SubscriptionHandle h, SimpleMessageContext messageContext, byte[] payload, Acknowledger acknowledger) {
		SimpleMessage message;
		try {
			message = cloudEvents.decode(messageContext, payload);
		} catch (RuntimeException | StackOverflowError e) {
			// remote input: no stack trace per message at WARNING, it would flood the log
			LOG.warning(() -> "Dropping an undecodable message on " + h + ": " + e.getClass().getSimpleName());
			LOG.log(Level.FINE, "Undecodable message", e);
			h.dropped();
			if (acknowledger != null) {
				acknowledger.reject(false);
			}
			return;
		}
		if (message == null) {
			// not a CloudEvent on an instance that takes CloudEvents only
			LOG.fine(() -> "Dropping a message that is no CloudEvent on " + h);
			h.dropped();
			if (acknowledger != null) {
				acknowledger.reject(false);
			}
			return;
		}
		message = AcknowledgeSupport.decide(h, message, acknowledger);
		if (message == null) {
			return;
		}
		try {
			long r;
			// one message at a time into a subscription; a lock, not a monitor, so that a virtual
			// worker waiting here does not pin its carrier thread
			h.deliveryLock.lock();
			try {
				r = h.sink().accept(message);
			} finally {
				h.deliveryLock.unlock();
			}
			if (!h.sink().takesItself()) {
				AcknowledgeSupport ack = AcknowledgeSupport.of(message);
				if (ack != null) {
					ack.taken();
				}
			}
			if (r < 0) {
				closeSubscription(h, null);
			}
		} catch (Exception e) {
			closeSubscription(h, e);
		}
	}

	// ------------------------------------------------------------------ runtime

	/**
	 * Returns the runtime DTO.
	 *
	 * @return the DTO
	 */
	public MessagingRuntimeDTO getRuntimeDTO() {
		MessagingRuntimeDTO dto = new MessagingRuntimeDTO();
		dto.replyToSubscriptions = new ReplyToSubscriptionDTO[0];
		OsgiBinding binding = osgi;
		if (binding != null) {
			binding.fill(dto);
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
		dto.streamPublishFailures = streamPublishFailures.get();
		dto.awaitingPublishes = publishWait.size();
		dto.replyStreams = replyTo.streamDTOs();
		dto.receiveWindow = receiveWindow();
		dto.subscriptions = subscriptions.stream().filter(h -> !h.isInternal()).map(SubscriptionHandle::toDTO)
				.toArray(SubscriptionDTO[]::new);
		return dto;
	}

	MessageContext defaultContext() {
		return defaultContext;
	}

	/** @return the reply-to support of this instance */
	public ReplyToPublisher replyToPublisher() {
		return replyTo;
	}

}
