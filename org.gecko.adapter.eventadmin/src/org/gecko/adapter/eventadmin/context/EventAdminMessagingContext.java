/*
 * Copyright (c) 2012 - 2024 Data In Motion and others.
 * All rights reserved. 
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 * SPDX-License-Identifier: EPL-2.0
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
