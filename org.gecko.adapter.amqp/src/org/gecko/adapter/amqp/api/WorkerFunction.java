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

package org.gecko.adapter.amqp.api;

import java.nio.ByteBuffer;
import java.util.function.Function;

import org.gecko.adapter.amqp.client.AMQPMessage;
import org.osgi.annotation.versioning.ConsumerType;

/**
 * Interface for an internal worker function that can be called, if a RPC request has been received on the provider side.
 * It executes the logic on the provider side and returns the result 
 * @author mark
 * @since 26.02.2024
 */
@ConsumerType
public interface WorkerFunction extends Function<AMQPMessage, ByteBuffer> {


}
