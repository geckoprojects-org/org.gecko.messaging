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

package org.gecko.osgi.messaging;

import java.nio.ByteBuffer;

/**
 * An message representation
 * @author Mark Hoffmann
 * @since 10.10.2017
 */
public interface Message {
	
	/**
	 * Returns the MQTT topic, the message belongs to
	 * @return the MQTT topic, the message belongs to
	 */
	public String topic();
	
	/**
	 * Returns the playload of the message as {@link ByteBuffer}
	 * @return the playload of the message as {@link ByteBuffer}
	 */
	public ByteBuffer payload();
	
	/**
	 * Returns the messages Context. This must never me null.
	 * @return the {@link MessagingContext} of this message
	 */
	public MessagingContext getContext();

}
