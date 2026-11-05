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
 * Signals that an operation asked for a feature the messaging instance does not support — see
 * {@link MessagingConstants#MESSAGING_FEATURE_PROPERTY}.
 */
public class FeatureNotSupportedException extends MessagingException {

	private static final long serialVersionUID = 1L;

	private final String feature;

	/**
	 * Creates the exception.
	 *
	 * @param feature the name of the feature that is not supported
	 * @param message the detail message
	 */
	public FeatureNotSupportedException(String feature, String message) {
		super(message);
		this.feature = feature;
	}

	/**
	 * Returns the feature that is not supported: a {@code FEATURE_} name of
	 * {@link MessagingConstants}, or — for a value no feature covers, such as an unknown channel
	 * type — the extension key carrying it.
	 *
	 * @return the feature name, or the extension key of an unknown value
	 */
	public String getFeature() {
		return feature;
	}

}
