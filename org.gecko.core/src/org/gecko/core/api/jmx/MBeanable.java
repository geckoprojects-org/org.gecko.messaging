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
package org.gecko.core.api.jmx;

/**
 * Interface that marks the implementation to return a JMX-compatible bean.
 * The Interface must end with name 'MBean'. The implementation must have the name of the interface
 * but without 'MBean' at the end.
 * @author Mark Hoffmann
 * @since 01.02.2019
 */
public interface MBeanable {
	
	/**
	 * Returns the JMX bean.
	 * @return the JMX bean.
	 */
	Object getMBean(); 

}
