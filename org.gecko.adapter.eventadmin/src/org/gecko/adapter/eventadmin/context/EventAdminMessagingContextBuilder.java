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
package org.gecko.adapter.eventadmin.context;

import java.util.HashMap;
import java.util.Map;

import org.gecko.osgi.messaging.MessagingContext;
import org.gecko.osgi.messaging.SimpleMessagingContext;
import org.gecko.osgi.messaging.SimpleMessagingContextBuilder;

/**
 * 
 * @author jalbert
 * @since 28 Jan 2019
 */
public class EventAdminMessagingContextBuilder extends SimpleMessagingContextBuilder implements HeaderMessagingContextBuilder {

	private Map<String, Object> headers = new HashMap<>();
 	
	public static final EventAdminMessagingContextBuilder builder() {
		return new EventAdminMessagingContextBuilder();
	}
	
	/* 
	 * (non-Javadoc)
	 * @see org.gecko.osgi.messaging.SimpleMessagingContextBuilder#build()
	 */
	@Override
	public MessagingContext build() {
		return buildContext(new EventAdminMessagingContextImpl());
	}
	
	/* 
	 * (non-Javadoc)
	 * @see org.gecko.osgi.messaging.SimpleMessagingContextBuilder#buildContext(org.gecko.osgi.messaging.SimpleMessagingContext)
	 */
	@Override
	protected <T extends SimpleMessagingContext> T buildContext(T ctx) {
		EventAdminMessagingContextImpl eventAdminContext = (EventAdminMessagingContextImpl) ctx;
		eventAdminContext.setHeaders(headers);
		return super.buildContext(ctx);
	}
	
	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.eventadmin.context.HeaderMessagingContextBuilder#headers(java.util.Map)
	 */
	@Override
	public HeaderMessagingContextBuilder headers(Map<String, Object> headers) {
		this.headers.putAll(headers);
		return this;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.eventadmin.context.HeaderMessagingContextBuilder#header(java.lang.String, java.lang.Object)
	 */
	@Override
	public HeaderMessagingContextBuilder header(String key, Object value) {
		headers.put(key, value);
		return this;
	}

}
