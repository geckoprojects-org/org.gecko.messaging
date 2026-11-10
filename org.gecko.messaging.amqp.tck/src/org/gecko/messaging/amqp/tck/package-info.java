/**
 * Copyright (c) 2012 - 2026 Data In Motion and others.
 * All rights reserved.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
/**
 * Broker control of the AMQP TCK run. The run needs the AMQP implementation.
 */
@RequireMessaging(protocol = "amqp")
package org.gecko.messaging.amqp.tck;

import org.gecko.messaging.api.annotations.RequireMessaging;
