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
package org.gecko.messaging.api.dto;

import org.osgi.dto.DTO;

/**
 * A channel: a topic or queue, with an optional extension like a routing key.
 *
 * @NotThreadSafe
 */
public class ChannelDTO extends DTO {

	/** The name of the channel. */
	public String name;

	/** The extension of the channel, like a routing key, or {@code null}. */
	public String extension;

	/** {@code true} if the channel is subscribed or bound on the broker right now. */
	public boolean connected;

}
