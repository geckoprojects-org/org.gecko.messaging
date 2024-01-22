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
package org.gecko.core.resources.file;

import java.io.File;
import java.net.URL;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.gecko.core.resources.ResourceConstants;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

/**
 * File watcher that registers the files as service 
 * @author Mark Hoffmann
 * @since 12.02.2019
 */
public class ServiceFileWatcher extends AbstractFileWatcher {

	private final Logger logger = Logger.getLogger(ServiceFileWatcher.class.getName());
	private final Map<String, ServiceRegistration<File>> fileRegistrations = new ConcurrentHashMap<String, ServiceRegistration<File>>();
	private final BundleContext ctx;

	/**
	 * Creates a new instance.
	 * @param directoryUrl the url for the directory to watch
	 * @param ctx the bundle context
	 */
	public ServiceFileWatcher(URL directoryUrl, BundleContext ctx) {
		super(directoryUrl);
		this.ctx = ctx;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.core.resources.file.AbstractFileWatcher#doUpdateFile(java.io.File)
	 */
	@Override
	protected void doUpdateFile(File file) {
		checkContext();
		registerFileService(file);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.core.resources.file.AbstractFileWatcher#doRemoveFile(java.io.File)
	 */
	@Override
	protected void doRemoveFile(File file) {
		checkContext();
		unregisterFileService(file.getName());
	}
	
	/* 
	 * (non-Javadoc)
	 * @see org.gecko.core.resources.file.AbstractFileWatcher#doStop()
	 */
	@Override
	protected void doStop() {
		fileRegistrations.keySet().forEach(this::unregisterFileService);
		fileRegistrations.clear();
	}
	
	/**
	 * Registers the given {@link File} as service
	 * @param file the file to register
	 */
	private void registerFileService(File file) {
		if (file == null) {
			logger.severe("Cannot register a null file as service");
			return;
		}
		ServiceRegistration<File> fileRegistration = null;
		String fileName = file.getName();
		Dictionary<String, Object> properties = new Hashtable<String, Object>();
		properties.put(ResourceConstants.RESOURCE_FILE, Boolean.TRUE);
		properties.put(ResourceConstants.RESOURCE_FILE_TIMESTAMP, file.lastModified());
		properties.put(ResourceConstants.RESOURCE_FILE_NAME, fileName);
		if (fileRegistrations.containsKey(fileName)) {
			fileRegistration = fileRegistrations.get(fileName);
			if (fileRegistration != null) {
				fileRegistration.setProperties(properties);
				logger.info(String.format("[%s] Updated configuration file registration", fileName));
			}
		} else {
			fileRegistration =  ctx.registerService(File.class, file, properties);
			fileRegistrations.put(fileName, fileRegistration);
			logger.info(String.format("[%s] Registered configuration file", fileName));
		}
	}
	
	/**
	 * Unregisters a file service for the given name
	 * @param fileName the file name
	 */
	private void unregisterFileService(String fileName) {
		ServiceRegistration<File> registration = fileRegistrations.remove(fileName);
		if (registration != null) {
			logger.info(String.format("[%s] Un-registering file", fileName));
			registration.unregister();
		}
	}
	
	private void checkContext() {
		if (ctx == null) {
			throw new IllegalStateException("The bundle context must not be null");
		}
	}

}
