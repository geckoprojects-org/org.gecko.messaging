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
package org.gecko.adapter.amqp.api.rpc;

import static java.util.Objects.isNull;
import static java.util.Objects.requireNonNull;
import static org.gecko.adapter.amqp.consumer.AMQPHelper.createFromMessage;
import static org.gecko.adapter.amqp.consumer.AMQPHelper.createMessage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.adapter.amqp.api.BasicReSubscribeConsumer;
import org.gecko.adapter.amqp.api.WorkerFunction;
import org.gecko.adapter.amqp.client.AMQPContext;
import org.gecko.adapter.amqp.client.AMQPMessage;
import org.gecko.adapter.amqp.consumer.AMQPMessageImpl;
import org.gecko.util.common.concurrent.NamedThreadFactory;
import org.osgi.service.component.ComponentServiceObjects;
import org.osgi.util.promise.Promise;
import org.osgi.util.promise.PromiseFactory;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;

/**
 * RPC consumer that handles a provided worker function on delivery and re-sends the result to the provide
 * reply-to address
 * @author Mark Hoffmann
 * @since 01.03.2024
 */
public class BasicRPCWorkerConsumer extends BasicReSubscribeConsumer<Void> {

	private static final Logger logger = Logger.getLogger(BasicRPCWorkerConsumer.class.getName());
	private final ComponentServiceObjects<WorkerFunction> functionCSO;
	private final PromiseFactory pf;
	private final AtomicInteger currentWorker = new AtomicInteger();
	private int maxWorkerCount = 3;

	/**
	 * Creates a new instance.
	 * @param channel
	 * @param consumerTag
	 * @param context
	 * @param function
	 * @param pf
	 */
	public BasicRPCWorkerConsumer(Channel channel, String consumerTag, AMQPContext context, ComponentServiceObjects<WorkerFunction> function, PromiseFactory pf) {
		super(channel, consumerTag, context);
		this.pf = isNull(pf) ? new PromiseFactory(Executors.newCachedThreadPool(NamedThreadFactory.newNamedFactory(String.format("RPSWorker-%s", consumerTag)))) : pf;
		requireNonNull(function);
		this.functionCSO = function;
	}

	/**
	 * Creates a new instance.
	 * @param channel
	 * @param consumerTag
	 * @param context
	 * @param function
	 */
	public BasicRPCWorkerConsumer(Channel channel, String consumerTag, AMQPContext context, ComponentServiceObjects<WorkerFunction> function) {
		this(channel, consumerTag, context, function, null);
	}
	
	/**
	 * Sets the maxWorkerCount.
	 * @param maxWorkerCount the maxWorkerCount to set
	 */
	public void setMaxWorkerCount(int maxWorkerCount) {
		this.maxWorkerCount = maxWorkerCount;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.api.BasicReSubscribeConsumer#shouldCloseConsumer()
	 */
	@Override
	public boolean shouldCloseConsumer() throws IOException {
		return currentWorker.get() == maxWorkerCount;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.api.BasicReSubscribeConsumer#shouldResubscribe()
	 */
	@Override
	public boolean shouldResubscribe() {
		return currentWorker.get() < maxWorkerCount;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.amqp.api.BasicReSubscribeConsumer#doHandleDelivery(java.lang.String, com.rabbitmq.client.Envelope, com.rabbitmq.client.BasicProperties, byte[])
	 */
	@Override
	protected void doHandleDelivery(final String consumerTag, Envelope envelope, BasicProperties properties, byte[] body)
			throws IOException {
		final long deliveryTag = envelope.getDeliveryTag();
		final String messageId = properties.getMessageId();
		Promise<AMQPMessageImpl> promise = pf.submit(()->{
			currentWorker.incrementAndGet();
			setProgress(true);
			return createMessage(envelope, properties, ByteBuffer.wrap(body));
		});
		promise.map(this::handleWorker).
			thenAccept(this::sendResponse).
			thenAccept(m->{
				boolean progress = currentWorker.decrementAndGet() > 0;
				setProgress(progress);
				checkResubscribe();
			}).
			onFailure(t->{
				getChannel().basicReject(deliveryTag, false);
				logger.log(Level.SEVERE, t, ()->String.format("Cannot send response for message '%s' on consumer '%s'", messageId, consumerTag));
			});

	}
	
	/**
	 * Handles the worker function. This method is called within a promise asynchronously
	 * @param request the request message
	 * @return the response message
	 */
	protected AMQPMessageImpl handleWorker(AMQPMessageImpl request) {
		WorkerFunction worker = functionCSO.getService();
		try {
			ByteBuffer response = worker.apply(request);
			AMQPMessageImpl responseMessage = createFromMessage(request, response);
			return responseMessage;
		} finally {
			functionCSO.ungetService(worker);
		}
	}

	/**
	 * Sends the response message.  This method is called within a promise asynchronously
	 * @param response the response message object to be sent
	 * @throws IOException
	 */
	protected void sendResponse(AMQPMessage response) throws IOException {
		String replyTo = response.getReplyTo();
		String correlationId = response.getCorrelationId();
		String messageId = response.getMessageId();
		String exchange = response.getExchange();
		if (isNull(exchange) || exchange.isBlank()) {
			exchange = "";
		}
		AMQP.BasicProperties replyProps = new AMQP.BasicProperties
				.Builder()
				.correlationId(correlationId)
				.messageId(messageId)
				.timestamp(new Date())
				.build();
		byte[] content = response.payload().array();
		Channel channel = getChannel();
		if (channel.isOpen()) {
			channel.basicPublish("", replyTo, replyProps, content);
		} else {
			throw new IllegalStateException("Channel is not open anymore");
		}
	}
	
}



