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
package org.gecko.core.resources;

/**
 * Constants for the resources
 * @author Mark Hoffmann
 * @since 11.02.2019
 */
public interface ResourceConstants {
	
	/** Service property that marks a resource url */
	public static final String RESOURCE_FILE = "gecko.resource.file";
	
	/** Service property that marks a resource file name */
	public static final String RESOURCE_FILE_NAME = "gecko.resource.fileName";
	
	/** Service for the last modification date of the file */
	public static final String RESOURCE_FILE_TIMESTAMP = "gecko.resource.timestamp";

}
