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
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamProvider;
import org.osgi.util.pushstream.SimplePushEventSource;

/**
 * 
 * @author mark
 * @since 04.01.2019
 */
public class POSManyFunction implements Function<AMQPMessage, PushStream<ByteBuffer>> {

	private final PushStreamProvider psp = new PushStreamProvider();
	private SimplePushEventSource<ByteBuffer> spes = null;

	/* 
	 * (non-Javadoc)
	 * @see java.util.function.Function#apply(java.lang.Object)
	 */
	@Override
	public PushStream<ByteBuffer> apply(AMQPMessage t) {
		String request = new String(t.payload().array());
		if (spes == null) {
			spes = psp.createSimpleEventSource(ByteBuffer.class);
		}
		spes.connectPromise().thenAccept((p)->{
			for (int i = 0; i < 5; i++) {
				String response = "Response: " + request + " (" + i + ")";
				ByteBuffer buffer = ByteBuffer.wrap(response.getBytes());
				spes.publish(buffer);
				System.out.println("publish " + response);
				try {
					Thread.sleep(1000l);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		});
		return psp.createStream(spes);
	}

}
