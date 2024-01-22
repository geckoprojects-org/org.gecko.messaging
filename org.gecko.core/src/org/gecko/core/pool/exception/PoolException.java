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
package org.gecko.core.pool.exception;

/**
 * 
 * @author Juergen Albert
 * @since 30 Oct 2019
 */
public class PoolException extends RuntimeException{

	/** serialVersionUID */
	private static final long serialVersionUID = 5655735298814854670L;

	/**
	 * Creates a new instance.
	 */
	public PoolException(String message) {
		super(message);
	}

	/**
	 * Creates a new instance.
	 * @param string
	 * @param e
	 */
	public PoolException(String message, InterruptedException e) {
		super(message, e);
	}
	
}
