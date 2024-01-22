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
package org.gecko.adapter.amqp.jmx;

import java.io.IOException;

/**
 * JMX bean interface for the AMQP service
 * @author Mark Hoffmann
 * @since 29.01.2019
 */
public interface AMQPServiceMetricMBean {
	
	String[] getChannels() throws IOException;
	int getNumberChannels() throws IOException;
	int getEventSourceBufferSize() throws IOException;
	String getEventSourceQueuePolicy() throws IOException;
	String getHost() throws IOException;
	String getVHost() throws IOException;
	int getPort() throws IOException;
	String getName() throws IOException;

}
