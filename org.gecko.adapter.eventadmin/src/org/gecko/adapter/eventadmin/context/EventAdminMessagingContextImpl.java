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

import org.gecko.osgi.messaging.SimpleMessagingContext;

/**
 * 
 * @author jalbert
 * @since 25 Jan 2019
 */
public class EventAdminMessagingContextImpl extends SimpleMessagingContext implements EventAdminMessagingContext{

	private Map<String, Object> headers = new HashMap<>();

	public void setHeaders(Map<String, Object> headers) {
		this.headers.putAll(headers);
	}
	
	/* 
	 * (non-Javadoc)
	 * @see org.gecko.adapter.eventadmin.context.EventAdminMessagingContext#getHeaders()
	 */
	@Override
	public Map<String, Object> getHeaders() {
		return headers;
	} 
}
