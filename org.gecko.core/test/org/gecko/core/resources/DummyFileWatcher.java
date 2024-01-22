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

import java.io.File;
import java.net.URL;
import java.util.concurrent.atomic.AtomicReference;

import org.gecko.core.resources.file.AbstractFileWatcher;

/**
 * 
 * @author mark
 * @since 12.02.2019
 */
public class DummyFileWatcher extends AbstractFileWatcher {
	
	public int addCount = 0;
	public int removeCount = 0;
	public AtomicReference<File> addedFile = new AtomicReference<File>();
	public AtomicReference<File> removedFile = new AtomicReference<File>();
	
	/**
	 * Creates a new instance.
	 */
	public DummyFileWatcher(URL directory) {
		super(directory);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.core.resources.file.AbstractFileWatcher#doUpdateFile(java.io.File)
	 */
	@Override
	protected void doUpdateFile(File file) {
		addedFile.set(file);
		addCount++;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.core.resources.file.AbstractFileWatcher#doRemoveFile(java.io.File)
	 */
	@Override
	protected void doRemoveFile(File file) {
		removedFile.set(file);
		removeCount++;
	}

}
