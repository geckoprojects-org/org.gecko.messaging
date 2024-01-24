/**
 * Copyright (c) 2012 - 2017 Data In Motion and others.
 * All rights reserved. 
 * 
 * This program and the accompanying materials are made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
package org.gecko.osgi.messaging;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;

import org.gecko.util.pushstream.PushStreamContext;
import org.osgi.util.function.Consumer;
import org.osgi.util.function.Predicate;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushbackPolicy;

/**
 * Interface for the message context builder
 * @author Mark Hoffmann
 * @since 10.10.2017
 */
public interface MessagingContextBuilder {
	
	/** Builds the message context
	 * @return the message context instance
	 */
	public MessagingContext build();
	
	public MessagingContextBuilder withBuffer(int size);
	
	public MessagingContextBuilder withBufferQueue(BlockingQueue<PushEvent<? extends Message>> bufferQueue);
	
	public MessagingContextBuilder withParallelism(int parallelism);
	
	public MessagingContextBuilder withExecutor(ExecutorService executor);
	
	public MessagingContextBuilder withScheduler(ScheduledExecutorService scheduler);

	public MessagingContextBuilder acknowledgeErrorFunction(BiConsumer<Throwable, Message> ackErrorFunction);

	public MessagingContextBuilder acknowledgeFunction(Consumer<Message> ackFunction);
	
	public MessagingContextBuilder negativeAcknowledgeFunction(Consumer<Message> mackFunction);

	public MessagingContextBuilder acknowledgeFilter(Predicate<Message> ackFilter);

	public MessagingContextBuilder replyTo(String replyToAddress);

	public MessagingContextBuilder correlationId(String correlationId);

	public MessagingContextBuilder contentEncoding(String contentEncoding);

	public MessagingContextBuilder contentType(String contentType);

	public MessagingContextBuilder exchange(String exchangeName, String routingKey);

	public MessagingContextBuilder queue(String queueName);

	public MessagingContextBuilder withPushbackPolicy(PushbackPolicy<Message, BlockingQueue<PushEvent<? extends Message>>> pushbackPolicy);
	
	public MessagingContextBuilder withPushstreamContext(PushStreamContext<Message> context);

}
