/**
 * Copyright (c) 2012 - 2019 Data In Motion and others.
 * All rights reserved. 
 * 
 * This program and the accompanying materials are made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
package org.gecko.core.api;

import java.util.Map;

/**
 * This helper class tries to find properties with certain names. To corresponding rules, it also 
 * looks into environment variables and Java system variables. If available it substitutes the values
 * with the one from the JVM or OS.
 * @author Mark Hoffmann
 * @since 01.02.2019
 */
public class PropertyHelper {
	
	private final static String ENV_PROPERTY_TEMPLATE = "%s.env";
	private final static String DEFAULT_PROP_SUFFIX = "env";
	private final String suffix;
	
	/**
	 * Creates a new instance.
	 */
	PropertyHelper() {
		this(DEFAULT_PROP_SUFFIX);
	}
	
	/**
	 * Creates a new instance.
	 */
	PropertyHelper(String suffix) {
		this.suffix = suffix == null ? DEFAULT_PROP_SUFFIX : suffix;
	}
	
	/**
	 * Creates the property helper with the default suffix
	 * @return the property helper with the default suffix
	 */
	public static PropertyHelper createHelper() {
		return new PropertyHelper();
	}
	
	/**
	 * Creates  the property helper with the given suffix
	 * @param suffix the suffix to be used
	 * @return the property helper with the given suffix
	 */
	public static PropertyHelper createHelper(String suffix) {
		return new PropertyHelper(suffix);
	}
	
	/**
	 * Returns the suffix.
	 * @return the suffix
	 */
	public String getSuffix() {
		return suffix;
	}
	
	public Object getValue(Map<? extends Object, ? extends Object> properties, String key, Object defaultValue) {
		if (properties == null || key == null) {
			return null;
		}
		String envKey = String.format(ENV_PROPERTY_TEMPLATE, key);
		Object envValue = properties.get(envKey);
		Object value = null;
		if (envValue != null) {
			value = System.getenv(envValue.toString());
			if (value == null) {
				value = System.getProperty(envValue.toString());
			}
		} else {
			value = properties.get(key);
		}
		return value == null ? defaultValue : value;
	}
	
	/**
	 * Returns the value for the given key or <code>null</code>, if nothing was found
	 * @param properties the properties map
	 * @param key the key to get the value from
	 * @return the value or <code>null</code>
	 */
	public Object getValue(Map<? extends Object, ? extends Object> properties, String key) {
		return getValue(properties, key, null);
	}

}
