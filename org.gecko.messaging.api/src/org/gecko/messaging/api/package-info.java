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
 * Messaging API: send messages to channels and receive them from channels of a third-party
 * messaging system (AMQP, MQTT, WebSocket, ...) over one protocol-neutral interface.
 * <p>
 * A <em>messaging instance</em> is a set of services that share one configuration (connection,
 * credentials, client settings). Every service of an instance carries the properties
 * {@value MessagingConstants#MESSAGING_NAME_PROPERTY},
 * {@value MessagingConstants#MESSAGING_PROVIDER_PROPERTY},
 * {@value MessagingConstants#MESSAGING_PROTOCOL_PROPERTY} and
 * {@value MessagingConstants#MESSAGING_FEATURE_PROPERTY}. An instance provides at least a
 * {@link MessagePublisher}, a {@link MessageSubscription}, a {@link MessageContextBuilder}
 * (prototype scope) and a {@link org.gecko.messaging.api.runtime.MessageServiceRuntime}.
 * <p>
 * Bundles wishing to use this package must list the package in the Import-Package header of the
 * bundle's manifest. For example:
 * <pre>
 * Import-Package: org.gecko.messaging.api; version="[1.0,2.0)"
 * </pre>
 */
@org.osgi.annotation.bundle.Export
@org.osgi.annotation.versioning.Version("1.0.0")
package org.gecko.messaging.api;
