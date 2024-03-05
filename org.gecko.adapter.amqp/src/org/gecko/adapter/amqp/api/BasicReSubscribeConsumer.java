/**
 * Copyright (c) 2012 - 2024 Data In Motion and others.
 * All rights reserved. 
 * 
 * This program and the accompanying materials are made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
package org.gecko.adapter.amqp.api;

import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.gecko.adapter.amqp.client.AMQPContext;
import org.osgi.util.promise.Deferred;
import org.osgi.util.promise.Promise;

import com.rabbitmq.client.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;

/**
 * Basic implementation that can be used as default behavior for rescheduling a connection after certain conditions
 * @author mark
 * @since 28.02.2024
 */
public abstract class BasicReSubscribeConsumer<T> extends DefaultConsumer implements ReSubscribeCallback<T> {

	private static final Logger logger = Logger.getLogger(BasicReSubscribeConsumer.class.getName());
	private final AtomicBoolean progress = new AtomicBoolean(false);
	private final AtomicBoolean cancelled = new AtomicBoolean(false);
	private Consumer<AMQPContext> disconnectCallback;
	private Deferred<T> deferred;
	protected final AMQPContext context;
	protected final String consumerTag;
	protected Envelope envelope = null;
	protected BasicProperties properties = null;
	protected byte[] body = null;

	/**
	 * Creates a new instance.
	 */
	public BasicReSubscribeConsumer(Channel channel, String consumerTag, AMQPContext context, Consumer<AMQPContext> disconnectCallback) {
		super(channel);
		requireNonNull(consumerTag);
		requireNonNull(context);
		this.context = context;
		this.consumerTag = consumerTag;
		this.disconnectCallback = disconnectCallback;
	}

	/**
	 * Creates a new instance.
	 */
	public BasicReSubscribeConsumer(Channel channel, String consumerTag, AMQPContext context) {
		this(channel, consumerTag, context, null);
	}

	/**
	 * Sets the deferred
	 * @param deferred
	 */
	public void setDeferred(Deferred<T> deferred) {
		requireNonNull(deferred);
		this.deferred = deferred;
	}

	/**
	 * Sets the disconnect callback.
	 * @param disconnectCallback the disconnect callback to set
	 */
	public void setDisconnectCallback(Consumer<AMQPContext> disconnectCallback) {
		this.disconnectCallback = disconnectCallback;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.api.ReSubscribeCallback#shouldCloseConsumer()
	 */
	@Override
	public boolean shouldCloseConsumer() throws IOException {
		// do not close the consumer per default
		return false;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.api.ReSubscribeCallback#shouldResubscribe()
	 */
	@Override
	public boolean shouldResubscribe() {
		// never re-subscribe
		return false;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.api.ReSubscribeCallback#getCloseValue()
	 */
	@Override
	public T getCloseValue() {
		return null;
	}

	/* 
	 * (non-Javadoc)
	 * @see com.rabbitmq.client.DefaultConsumer#handleCancelOk(java.lang.String)
	 */
	@Override
	public void handleCancelOk(String consumerTag) {
		cancelled.compareAndSet(false, true);
		checkResubscribe();
	}

	/**
	 * Executes the disconnect of the channel
	 */
	private void doDisconnect() {
		Deferred<T> deferred = getDeferred();
		try {
			preDisconnect();
			if (nonNull(disconnectCallback)) {
				disconnectCallback.accept(context);
			}
			postDisconnect();
		} catch (Exception e) {
			deferred.fail(e);
		}
	}

	/* 
	 * (non-Javadoc)
	 * @see com.rabbitmq.client.DefaultConsumer#handleDelivery(java.lang.String, com.rabbitmq.client.Envelope, com.rabbitmq.client.AMQP.BasicProperties, byte[])
	 */
	@Override
	public void handleDelivery(String consumerTag, Envelope envelope,
			com.rabbitmq.client.AMQP.BasicProperties properties, byte[] body) throws IOException {
		this.envelope = envelope;
		this.properties = properties;
		this.body = body;
		final long deliveryTag = envelope.getDeliveryTag();
		final String routingKey = envelope.getRoutingKey();
		final String exchange = envelope.getExchange();
		final String contentType = properties.getContentType();
		logger.fine(()->String.format("Tag: '%s'; exchange: '%s'; key: '%s'; contentType: '%s'; deliveryTag: '%s'", consumerTag, exchange, routingKey, contentType, deliveryTag));
		// delegates the event handling
		doHandleDelivery(consumerTag, envelope, properties, body);
		AMQPProperties amqpProps = context.getProperties();
		if (nonNull(amqpProps) && !amqpProps.autoAcknowledge()) {
			getChannel().basicAck(envelope.getDeliveryTag(), false);
		}
		// checks whether to close the consumer
		doShouldCloseConsumer();
	}
	
	protected boolean isCancelled() {
		return cancelled.get();
	}
	
	/**
	 * Checks if a re-subscription should be triggered by resolving the re-su
	 * Delegates to the re-subscribe method to have the possibility to wait
	 * until the moment, the re-subscribe should happen. If no {@link Promise} is returned,
	 * the re-subscription will happen immediately.
	 * We can only re-subscribe, if no further operation is in progress. If you are in RPC mode,
	 * you might want to send a response back to a reply address. Though you don't want to get disconnected 
	 * upfront.
	 */
	public final void checkResubscribe() {
		if (shouldResubscribe() && 
				isCancelled() && 
				!isProgress()) {
			doDisconnect();
			deferred.resolve(getCloseValue());
		}
	}

	/**
	 * Delegated from the original consumer to handle the message
	 * @param consumerTag the consumer tag
	 * @param envelope the message envelope
	 * @param properties the message properties
	 * @param body the body content
	 * @throws IOException
	 */
	abstract protected void doHandleDelivery(String consumerTag, Envelope envelope, BasicProperties properties,
	byte[] body) throws IOException;

	/**
	 * Called before the disconnect callback is executed 
	 * @throws IOException
	 */
	protected void preDisconnect() throws IOException {
		// Nothing to do here
	}

	/**
	 * Called after the disconnect callback was called
	 * @throws IOException
	 */
	protected void postDisconnect() throws IOException {
		// Nothing to do here
	}

	/**
	 * Returns the {@link Deferred}
	 * @return the {@link Deferred}
	 */
	protected Deferred<T> getDeferred() {
		requireNonNull(deferred);
		return deferred;
	}
	
	protected void setProgress(boolean _progress) {
		progress.set(_progress);
	}
	
	private boolean isProgress() {
		return progress.get();
	}

	/**
	 * Evaluates, whether the consumer should be closed or not.
	 * @return <code>true</code>, if the consumer should be closed, otherwise <code>false</code>
	 */
	private boolean doShouldCloseConsumer() throws IOException {
		return shouldCloseConsumer() ? doCancelConsumer() : false;
	}
	
	/**
	 * Called when the consumer is to be cancelled. This triggers the 
	 * {@link DefaultConsumer#handleCancelOk(String)} callback.
	 * @return <code>true</code> on successful closing, otherwise <code>false</code>s
	 */
	private boolean doCancelConsumer() throws IOException {
		Deferred<T> deferred = getDeferred();
		try {
			getChannel().basicCancel(consumerTag);
			return true;
		} catch (Exception e) {
			deferred.fail(e);
			return false;
		}
	}

}
