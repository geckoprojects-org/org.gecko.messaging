/**
 * Copyright (c) 2012 - 2019 Data In Motion and others.
 * All rights reserved. 
 * 
 * This program and the accompanying materials are made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
package org.gecko.core.pushstream.source;

import java.util.function.BiConsumer;

import org.gecko.core.pushstream.PushStreamContext;
import org.osgi.util.function.Consumer;
import org.osgi.util.function.Predicate;
import org.osgi.util.promise.Promise;
import org.osgi.util.pushstream.PushEventConsumer;
import org.osgi.util.pushstream.PushStreamProvider;
import org.osgi.util.pushstream.SimplePushEventSource;

/**
 * Event source that acknowledges the data
 * @author Mark Hoffmann
 */
public class AcknowledgingEventSource<T> implements SimplePushEventSource<T>{

	private final PushStreamProvider psp = new PushStreamProvider();
	private SimplePushEventSource<T> eventSource;
	private PushStreamContext<T> context;
	private Predicate<T> ackFilter;
	private Consumer<T> ackFunction;
	private Consumer<T> nackFunction;
	private BiConsumer<Throwable, T> ackErrorFunction;

	public AcknowledgingEventSource(SimplePushEventSource<T> eventSource, PushStreamContext<T> context) {
		this.eventSource = eventSource;
		this.context = context;
		initializeContext();
	}

	public AcknowledgingEventSource(Class<T> messageClass, PushStreamContext<T> context) {
		this.eventSource = psp.buildSimpleEventSource(messageClass).build();
		this.context = context;
		initializeContext();
	}

	/**
	 * Provides the acknowledge filter predicate
	 * @param ackFilter the predicate, can be <code>null</code>
	 * @return the event source instance
	 */
	public AcknowledgingEventSource<T> acknowledgeFilter(Predicate<T> ackFilter) {
		this.ackFilter = ackFilter;
		return this;
	}

	/**
	 * Provides the acknowledge consumer function
	 * @param ackFunction the acknowledge function, can be <code>null</code>
	 * @return the event source instance
	 */
	public AcknowledgingEventSource<T> acknowledge(Consumer<T> ackFunction) {
		this.ackFunction = ackFunction;
		return this;
	}

	/**
	 * Provides the acknowledge consumer function
	 * @param ackFunction the acknowledge function, can be <code>null</code>
	 * @return the event source instance
	 */
	public AcknowledgingEventSource<T> negativeAcknowledge(Consumer<T> nackFunction) {
		this.nackFunction = nackFunction;
		return this;
	}

	/**
	 * Provides the acknowledge error consumer function
	 * @param ackFunction the acknowledge error function, can be <code>null</code>
	 * @return the event source instance
	 */
	public AcknowledgingEventSource<T> acknowledgeError(BiConsumer<Throwable, T> ackErrorFunction) {
		this.ackErrorFunction = ackErrorFunction;
		return this;
	}

	/**
	 * Publishes an event
	 * @param event the event to publish
	 */
	public void publish(T event) {
		if (eventSource == null) {
			throw new IllegalStateException("Cannot publish message for a null push event source");
		}
		try {
			if (ackFilter == null) {
				doPublishAndAck(event);
			} else {
				if (ackFilter.test(event)) {
					doPublishAndAck(event);
				} else {
					doNegativeAcknowledge(event);
				}
			}
		} catch (Exception e) {
			throw new IllegalStateException("Error testing data for acknowledge", e);
		}
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushEventSource#open(org.osgi.util.pushstream.PushEventConsumer)
	 */
	@Override
	public AutoCloseable open(PushEventConsumer<? super T> aec) throws Exception {
		return eventSource.open(aec);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.SimplePushEventSource#close()
	 */
	@Override
	public void close() {
		eventSource.close();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.SimplePushEventSource#endOfStream()
	 */
	@Override
	public void endOfStream() {
		eventSource.endOfStream();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.SimplePushEventSource#error(java.lang.Throwable)
	 */
	@Override
	public void error(Throwable t) {
		eventSource.error(t);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.SimplePushEventSource#isConnected()
	 */
	@Override
	public boolean isConnected() {
		return eventSource.isConnected();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.SimplePushEventSource#connectPromise()
	 */
	@Override
	public Promise<Void> connectPromise() {
		return eventSource.connectPromise();
	}

	private void initializeContext() {
		if (context != null) {
			this.ackFilter = context.getAcknowledgeFilter();
			this.ackFunction = context.getAcknowledgeFunction();
		}
	}

	/**
	 * Publishes an event and acknowledges the event 
	 * @param event the event to be published
	 */
	private void doPublishAndAck(T event) {
		try {
			eventSource.publish(event);
			if (ackFunction != null) {
				ackFunction.accept(event);
			}
		} catch (Exception e) {
			if (ackErrorFunction != null) {
				ackErrorFunction.accept(e, event);
			}
		}
	}

	/**
	 * Executes a negative acknowledge if available
	 * @param event the event to publish
	 */
	private void doNegativeAcknowledge(T event) {
		if (nackFunction != null) {
			try {
				nackFunction.accept(event);
			} catch (Exception e) {
				if (ackErrorFunction != null) {
					ackErrorFunction.accept(e, event);
				}
			}
		}
	}

}
