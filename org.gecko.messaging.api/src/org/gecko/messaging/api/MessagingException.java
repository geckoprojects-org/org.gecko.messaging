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
package org.gecko.messaging.api;

/**
 * Signals that a messaging operation failed.
 */
public class MessagingException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates the exception.
	 *
	 * @param message the detail message
	 */
	public MessagingException(String message) {
		super(message);
	}

	/**
	 * Creates the exception.
	 *
	 * @param message the detail message
	 * @param cause the cause
	 */
	public MessagingException(String message, Throwable cause) {
		super(message, cause);
	}

}
