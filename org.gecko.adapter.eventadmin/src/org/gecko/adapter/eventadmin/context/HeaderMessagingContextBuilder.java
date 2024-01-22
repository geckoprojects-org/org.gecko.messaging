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

import java.util.Map;

import org.gecko.osgi.messaging.MessagingContextBuilder;

/**
 * 
 * @author Juergen Albert
 * @since 28 Jan 2019
 */
public interface HeaderMessagingContextBuilder extends MessagingContextBuilder {

	public HeaderMessagingContextBuilder headers(Map<String, Object> headers);

	public HeaderMessagingContextBuilder header(String key, Object value);
	
}
