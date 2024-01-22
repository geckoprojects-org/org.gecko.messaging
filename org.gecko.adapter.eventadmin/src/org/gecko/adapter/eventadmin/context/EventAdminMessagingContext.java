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

import org.gecko.osgi.messaging.MessagingContext;

/**
 * A special context for EventAdmin messaging
 * @author Juergen Albert
 * @since 25 Jan 2019
 */
public interface EventAdminMessagingContext extends MessagingContext {

	/**
	 * Returns all set message headers. This {@link Map} must never be null 
	 * @return the {@link Map} of headers
	 */
	public Map<String, Object> getHeaders();
	
}
