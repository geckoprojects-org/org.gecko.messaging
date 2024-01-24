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
package org.gecko.adapter.amqp.tests;

import java.nio.ByteBuffer;
import java.util.function.Function;

import org.gecko.adapter.amqp.client.AMQPMessage;

/**
 * 
 * @author mark
 * @since 04.01.2019
 */
public class POSFunction implements Function<AMQPMessage, ByteBuffer> {

	/* 
	 * (non-Javadoc)
	 * @see java.util.function.Function#apply(java.lang.Object)
	 */
	@Override
	public ByteBuffer apply(AMQPMessage t) {
		String request = new String(t.payload().array());
		String response = "Response: " + request;
		return ByteBuffer.wrap(response.getBytes());
	}

}
