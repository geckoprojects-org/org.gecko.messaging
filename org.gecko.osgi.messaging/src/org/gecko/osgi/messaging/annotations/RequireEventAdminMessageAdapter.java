/**
 * Copyright (c) 2012 - 2018 Data In Motion and others.
 * All rights reserved. 
 * 
 * This program and the accompanying materials are made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
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

@Requirement(namespace=MessagingConstants.CAPABILITY_NAMESPACE)
@Retention(CLASS)
@Target({ TYPE, PACKAGE })
/**
 * Require capability annotation for the message adapter
 * @author Mark Hoffmann
 * @since 15.02.2018
 */
@Requirement(
	namespace = MessagingConstants.CAPABILITY_NAMESPACE,
	name=MessagingConstants.EVENTADMIN_ADAPTER,
	version=MessagingConstants.EVENTADMIN_ADAPTER_VERSION
		)
public @interface RequireEventAdminMessageAdapter {

}
