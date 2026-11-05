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
package org.gecko.messaging.api.annotations;

import static java.lang.annotation.ElementType.PACKAGE;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.CLASS;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.gecko.messaging.api.MessagingConstants;
import org.osgi.annotation.bundle.Requirement;
import org.osgi.namespace.implementation.ImplementationNamespace;

/**
 * Requires a messaging implementation of this specification. Implementations provide the
 * capability {@code osgi.implementation;osgi.implementation=osgi.messaging} with the attribute
 * {@code osgi.messaging.protocol} listing their protocols.
 */
@Documented
@Retention(CLASS)
@Target({ TYPE, PACKAGE })
@Requirement(namespace = ImplementationNamespace.IMPLEMENTATION_NAMESPACE, //
		name = MessagingConstants.MESSAGING_IMPLEMENTATION, //
		filter = "(&(osgi.implementation=" + MessagingConstants.MESSAGING_IMPLEMENTATION + ")${frange;"
				+ MessagingConstants.MESSAGING_SPECIFICATION_VERSION + "}${if;${protocol};(osgi.messaging.protocol=${protocol})})")
public @interface RequireMessaging {

	/**
	 * The protocol the implementation must speak, e.g. {@code mqtt}; empty for any.
	 *
	 * @return the protocol
	 */
	String protocol() default "";

}
