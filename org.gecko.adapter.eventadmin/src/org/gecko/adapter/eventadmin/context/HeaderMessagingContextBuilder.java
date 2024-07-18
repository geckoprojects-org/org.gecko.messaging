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
