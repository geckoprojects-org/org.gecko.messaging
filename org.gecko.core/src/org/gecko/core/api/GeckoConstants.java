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

/**
 * Generic 
 * @author mark
 * @since 11.02.2019
 */
public interface GeckoConstants {
	
	/** Property to define the base directory */
	public static final String PROP_GECKO_BASE_DIR = "gecko.base.dir";
	/** Property to define the configuration directory */
	public static final String PROP_GECKO_CONFIG_DIR = "gecko.conf.dir";
	/** Property to define the data directory */
	public static final String PROP_GECKO_DATA_DIR = "gecko.data.dir";
	
	/** Service property the is for the URL property */
	public static final String PROP_SERVICE_URL = "gecko.url";
	
	/** The default relative path for the base */
	public static final String DEFAULT_GECKO_BASE_DIR = ".gecko";
	/** The default relative path for the data directory */
	public static final String DEFAULT_GECKO_DATA_DIR = DEFAULT_GECKO_BASE_DIR + "/data";
	/** The default relative path for the configuration directory */
	public static final String DEFAULT_GECKO_CONFIG_DIR = DEFAULT_GECKO_BASE_DIR + "/etc";

}
