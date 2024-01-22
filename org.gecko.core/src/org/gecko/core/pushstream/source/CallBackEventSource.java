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
import java.util.function.Consumer;

import org.osgi.util.promise.Promise;
import org.osgi.util.pushstream.PushEventConsumer;
import org.osgi.util.pushstream.SimplePushEventSource;

/**
 * A wrapper for a {@link SimplePushEventSource} that has certain callback methods on {@link SimplePushEventSource#open(PushEventConsumer)}, {@link SimplePushEventSource#close()}. 
 * It also provides a Callback, after a {@link PushEventConsumer} received its close event
 * @author Juergen Albert
 * @since 21 Jan 2019
 */
public class CallBackEventSource<T> implements SimplePushEventSource<T>{

	private final SimplePushEventSource<T> root;
	private BiConsumer<CallBackEventSource<T>, PushEventConsumer<? super T>> openCallBack;
	private Consumer<CallBackEventSource<T>> closeCallback;
	private BiConsumer<CallBackEventSource<T>, PushEventConsumer<? super T>> consumerClosedCallback;
	
	/**
	 * Creates a new instance.
	 * @param root the {@link SimplePushEventSource} every call gets delegated to
	 * @param openCallBack this function is called every time {@link SimplePushEventSource#open(PushEventConsumer)} is called 
	 * @param closeCallback will be called before the source close will be called
	 * @param consumerClosedCallback will be called after the close event was send to a consumer
	 */
	public CallBackEventSource(SimplePushEventSource<T> root, 
			BiConsumer<CallBackEventSource<T>, PushEventConsumer<? super T>> openCallBack, 
			Consumer<CallBackEventSource<T>> closeCallback,
			BiConsumer<CallBackEventSource<T>, PushEventConsumer<? super T>> consumerClosedCallback) {
		this.root = root;
		this.openCallBack = openCallBack;
		this.closeCallback = closeCallback;
		this.consumerClosedCallback = consumerClosedCallback;
	}
	
	
	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.SimplePushEventSource#close()
	 */
	@Override
	public void close() {
		if(closeCallback != null) {
			closeCallback.accept(this);
		}
		root.close();
	}
	
	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.SimplePushEventSource#endOfStream()
	 */
	@Override
	public void endOfStream() {
		root.endOfStream();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.SimplePushEventSource#error(java.lang.Throwable)
	 */
	@Override
	public void error(Throwable t) {
		root.error(t);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.SimplePushEventSource#isConnected()
	 */
	@Override
	public boolean isConnected() {
		return root.isConnected();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.SimplePushEventSource#connectPromise()
	 */
	@Override
	public Promise<Void> connectPromise() {
		return root.connectPromise();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.PushEventSource#open(org.osgi.util.pushstream.PushEventConsumer)
	 */
	@Override
	public AutoCloseable open(PushEventConsumer<? super T> aec) throws Exception {
		if(openCallBack != null) {
			openCallBack.accept(this, aec);
		}
		AutoCloseable closable = root.open(aec);
		return () -> {
			closable.close();
			if(consumerClosedCallback != null) {
				consumerClosedCallback.accept(this, aec);
			}
		};
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.util.pushstream.SimplePushEventSource#publish(java.lang.Object)
	 */
	@Override
	public void publish(T t) {
		root.publish(t);
	}

}
