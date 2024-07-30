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
