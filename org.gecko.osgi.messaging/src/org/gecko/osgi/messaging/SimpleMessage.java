/**
 * Copyright (c) 2012 - 2017 Data In Motion and others.
 * All rights reserved. 
 * 
 * This program and the accompanying materials are made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
package org.gecko.osgi.messaging;

import java.nio.ByteBuffer;

/**
 * Simple Message implementation
 * @author Mark Hoffmann
 * @since 10.10.2017
 */
public class SimpleMessage implements Message {
	
	private final String topic;
	private final ByteBuffer payload;
	private final MessagingContext context;

	public SimpleMessage(String topic, ByteBuffer payload) {
		this(topic, payload, SimpleMessagingContextBuilder.builder().build());
	}
	
	public SimpleMessage(String topic, ByteBuffer payload, MessagingContext context) {
		this.topic = topic;
		this.payload = payload;
		this.context = context;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.osgi.messaging.Message#topic()
	 */
	@Override
	public String topic() {
		return topic;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.osgi.messaging.Message#payload()
	 */
	@Override
	public ByteBuffer payload() {
		return payload;
	}

	@Override
	public MessagingContext getContext() {
		return context;
	}

}
