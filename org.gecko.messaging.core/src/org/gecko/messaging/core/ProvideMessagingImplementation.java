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
package org.gecko.messaging.core;

import static java.lang.annotation.ElementType.PACKAGE;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.CLASS;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.gecko.messaging.api.MessagingConstants;
import org.osgi.annotation.bundle.Attribute;
import org.osgi.annotation.bundle.Capability;
import org.osgi.namespace.implementation.ImplementationNamespace;
import org.osgi.namespace.service.ServiceNamespace;

/**
 * Put on the component of a protocol bundle: announces the implementation of the messaging
 * specification for the protocol, and the services every instance registers (they are registered
 * programmatically, so bnd cannot see them).
 */
@Documented
@Retention(CLASS)
@Target({ TYPE, PACKAGE })
@Capability(namespace = ImplementationNamespace.IMPLEMENTATION_NAMESPACE, //
		name = MessagingConstants.MESSAGING_IMPLEMENTATION, //
		version = MessagingConstants.MESSAGING_SPECIFICATION_VERSION, //
		uses = { org.gecko.messaging.api.Message.class })
@Capability(namespace = ServiceNamespace.SERVICE_NAMESPACE, //
		attribute = "objectClass:List<String>=\"org.gecko.messaging.api.MessagePublisher,org.gecko.messaging.api.MessageSubscription,org.gecko.messaging.api.MessageContextBuilder,org.gecko.messaging.api.runtime.MessageServiceRuntime\"", //
		uses = { org.gecko.messaging.api.Message.class, org.gecko.messaging.api.runtime.MessageServiceRuntime.class })
public @interface ProvideMessagingImplementation {

	/**
	 * The protocols of the implementation.
	 *
	 * @return the protocols
	 */
	@Attribute(MessagingConstants.MESSAGING_PROTOCOL_PROPERTY)
	String[] protocol();

}
