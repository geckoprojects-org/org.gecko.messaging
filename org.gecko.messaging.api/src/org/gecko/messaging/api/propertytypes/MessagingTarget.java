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
package org.gecko.messaging.api.propertytypes;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.CLASS;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.osgi.service.component.annotations.ComponentPropertyType;

/**
 * Component property type for
 * {@link org.gecko.messaging.api.MessagingConstants#MESSAGING_TARGET_PROPERTY}: selects the
 * messaging instances a whiteboard service binds to.
 */
@ComponentPropertyType
@Documented
@Retention(CLASS)
@Target(TYPE)
public @interface MessagingTarget {

	/** Prefix of the property name. */
	String PREFIX_ = "osgi.";

	/**
	 * The filter, matched against the service properties of the instance's runtime service, e.g.
	 * {@code (osgi.messaging.name=sensors)}.
	 *
	 * @return the filter
	 */
	String value();

}
