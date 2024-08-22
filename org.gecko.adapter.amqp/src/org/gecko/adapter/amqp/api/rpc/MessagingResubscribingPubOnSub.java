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

package org.gecko.adapter.amqp.api.rpc;

import org.gecko.osgi.messaging.MessagingRPCPubOnSub;
import org.osgi.annotation.versioning.ProviderType;

/**
 * Messaging service interface to RPC publish on subscribe.
 * This is just a marker interface
 * @author Mark Hoffmann
 * @since 10.10.2017
 */
@ProviderType
public interface MessagingResubscribingPubOnSub extends MessagingRPCPubOnSub {
	
	public void resubscribe(String topic);
	
}
