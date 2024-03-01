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
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.gecko.adapter.amqp.client.AMQPContext;
import org.osgi.util.promise.Deferred;

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
		return false;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.api.ReSubscribeCallback#preCloseConsumer()
	 */
	@Override
	public void preCloseConsumer() throws IOException {
		// Nothing to do here
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.api.ReSubscribeCallback#closeConsumer()
	 */
	@Override
	public void closeConsumer() throws IOException {
		// Nothing to do here
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.api.ReSubscribeCallback#closeChannel()
	 */
	@Override
	public void closeChannel() throws IOException {
		// Nothing to do here
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.api.ReSubscribeCallback#getCloseValue()
	 */
	@Override
	public T getCloseValue() {
		return null;
	}

	/**
	 * Evaluates, whether the consumer should be closed or not.
	 * @param envelop
	 * @param properties
	 * @param body
	 * @return <code>true</code>, if the consumer should be closed, otherwise <code>false</code>
	 */
	protected boolean doShouldCloseConsumer() throws IOException {
		if (shouldCloseConsumer()) {
			doCancelConsumer();
		}
		return shouldCloseConsumer();
	}
	
	/**
	 * Called when the consumer is to be cancelled
	 * @return <code>true</code> on successful closing, otherwise <code>false</code>s
	 */
	protected boolean doCancelConsumer() throws IOException {
		Deferred<T> deferred = getDeferred();
		try {
			getChannel().basicCancel(consumerTag);
			return true;
		} catch (Exception e) {
			deferred.fail(e);
			return false;
		}
	}
	
	/* 
	 * (non-Javadoc)
	 * @see com.rabbitmq.client.DefaultConsumer#handleCancelOk(java.lang.String)
	 */
	@Override
	public void handleCancelOk(String consumerTag) {
		Deferred<T> deferred = getDeferred();
		try {
			preCloseConsumer();
			Consumer<AMQPContext> dcc = getDisconnectCallback();
			if (nonNull(dcc)) {
				dcc.accept(context);
			}
			closeConsumer();
			deferred.resolve(getCloseValue());
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

	/**
	 * Returns the {@link Deferred}
	 * @return the {@link Deferred}
	 */
	Deferred<T> getDeferred() {
		requireNonNull(deferred);
		return deferred;
	}

	/**
	 * Returns the disconnect callback or <code>null</code>
	 * @return the {@link BiConsumer} or <code>null</code>
	 */
	Consumer<AMQPContext> getDisconnectCallback() {
		return disconnectCallback;
	}

	abstract protected void doHandleDelivery(String consumerTag, Envelope envelope, BasicProperties properties,
			byte[] body) throws IOException;

}
