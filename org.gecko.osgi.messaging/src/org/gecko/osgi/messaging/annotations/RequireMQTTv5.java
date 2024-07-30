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

package org.gecko.osgi.messaging.annotations;

import static java.lang.annotation.ElementType.PACKAGE;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.CLASS;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.gecko.osgi.messaging.MessagingConstants;
import org.osgi.annotation.bundle.Requirement;

/**
 * 
 * @author grune
 * @since Jul 3, 2024
 */
@Requirement(namespace = MessagingConstants.CAPABILITY_NAMESPACE, name = "mqtt.adapter", filter = "(mqttVersion=5)")
@Retention(CLASS)
@Target({ TYPE, PACKAGE })
public @interface RequireMQTTv5 {

}
