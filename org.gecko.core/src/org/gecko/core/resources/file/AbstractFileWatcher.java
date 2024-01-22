/**
 * Copyright (c) 2012 - 2018 Data In Motion Consulting.
 * All rights reserved. 
 * 
 * This program and the accompanying materials are made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Data In Motion Consulting - initial API and implementation
 */
package org.gecko.core.resources.file;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.core.api.GeckoConstants;
import org.gecko.core.api.concurrent.NamedThreadFactory;
import org.gecko.core.api.concurrent.ExecutorHelper;

/**
 * Abstract file watcher implementation for the files. It watches all files in the given folder as directory url.
 * In addition to that a file name filter can be provided, that is used to filter relevant files from unwanted.
 * As default a {@link BasicFilenameFilter} is used;
 * @author Mark Hoffmann
 */
public abstract class AbstractFileWatcher implements Runnable {

	private final Logger logger = Logger.getLogger(AbstractFileWatcher.class.getName());
	private final Map<String, File> fileMap = new ConcurrentHashMap<String, File>();
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final String name;
	private final ExecutorService es;
	private FilenameFilter fileNameFilter = new BasicFilenameFilter(new String[0]);
	private WatchService fileWatcher;
	private URL directory;

	public AbstractFileWatcher(URL directoryUrl) {
		this(directoryUrl, null);
	}
	
	public AbstractFileWatcher(URL directoryUrl, String name) {
		if (directoryUrl == null) {
			throw new IllegalArgumentException("Directory URL must not be null");
		}
		this.directory = directoryUrl;
		this.name = name == null ? directory.getPath().replace("/", "_").substring(1) : name;
		this.es = Executors.newSingleThreadExecutor(NamedThreadFactory.newNamedFactory("FileWatcher-" + name));
	}
	
	/**
	 * Returns the name.
	 * @return the name
	 */
	public String getName() {
		return name;
	}
	
	/**
	 * Sets the fileNameFilter.
	 * @param fileNameFilter the fileNameFilter to set
	 */
	public void setFileNameFilter(FilenameFilter fileNameFilter) {
		this.fileNameFilter = fileNameFilter;
	}
	
	/**
	 * Returns the fileNameFilter.
	 * @return the fileNameFilter
	 */
	public FilenameFilter getFileNameFilter() {
		return fileNameFilter;
	}
	
	/**
	 * Returns the directory.
	 * @return the directory
	 */
	public URL getDirectory() {
		return directory;
	}

	/**
	 * Starts the file watcher
	 */
	public void start() {
		if (this.directory == null) {
			throw new IllegalStateException("File watcher has a null directory URL");
		}
		String url = directory.getPath().replace("/", "_").substring(1);
		if (!running.compareAndSet(false, true)) {
			throw new IllegalStateException(String.format("[%s] File watcher was already started", url));
		}
		try {
			doStart();
			es.submit(this);
		} catch (Exception e) {
			running.set(false);
			throw e;
		}
	}

	/**
	 * Stops the watcher and disposes all resources
	 */
	public void stop() {
		try {
			disposeFolderWatcher();
			ExecutorHelper.shutdownExecutorServiceWithAwait(es, 300);
			fileMap.keySet().forEach(this::removeFile);
			fileMap.clear();
			doStop();
		} finally {
			running.set(false);
		}
	}

	/**
	 * Sets a list of file names that are valid to be watched. This overwrites the fileNameFilter with the {@link BasicFilenameFilter}.
	 * If you want to use a more sophisticated filtering, use the setFileNameFilter without this method.
	 * @param filterList the list of files that can be watched
	 */
	public void setFilterList(String[] filterList) {
		if (filterList == null) {
			this.fileNameFilter = new BasicFilenameFilter(new String[0]);
		} else {
			this.fileNameFilter = new BasicFilenameFilter(filterList);
		}
	}
	
	/* (non-Javadoc)
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		if (directory == null) {
			return;
		}
		while (running.get()) {
			File confFolder = registerFileWatcher();
			if (confFolder == null) {
				logger.warning(String.format("[%s] Registering the file watcher didn't succeed. File watcher stops running", directory));
				break;
			}
			readConfigDirectory();
			try {
				WatchKey key;
				while ((key = fileWatcher.take()) != null) {
					if (!running.get()) {
						break;
					}
					if (!confFolder.exists()) {
						logger.log(Level.INFO, String.format("[%s] The config folder was obviously deleted in the meantime", GeckoConstants.PROP_GECKO_CONFIG_DIR));
						disposeFolderWatcher();
						break;
					}
					for (WatchEvent<?> event : key.pollEvents()) {
						Path p = (Path) event.context();
						String fileName = p.toString();
						if (!fileNameFilter.accept(null, fileName)) {
							continue;
						}
						File file = confFolder.toPath().resolve(p).toFile();
						if (event.kind().equals(StandardWatchEventKinds.ENTRY_CREATE) || 
								event.kind().equals(StandardWatchEventKinds.ENTRY_MODIFY)) {
							updateFile(file);
						} else if (event.kind().equals(StandardWatchEventKinds.ENTRY_DELETE)) {
							removeFile(fileName);
						} else {
							logger.warning(String.format("[%] Detected an unsupported change of the file", event.context().toString()));
						}
					}
					key.reset();
				}
			} catch (InterruptedException e) {
				logger.log(Level.SEVERE, String.format("[%s] Watching the configuaration folder was interrupted", GeckoConstants.PROP_GECKO_CONFIG_DIR), e);
			}
		}
		stop();
	}
	
	/**
	 * Registers the given {@link File} as service. A callback to be used by clients
	 * @param file the file to register
	 */
	abstract protected void doUpdateFile(File file);

	/**
	 * Un-registers the given {@link File} as removed. A callback to be used by clients
	 * @param file the file to un-register, can be <code>null</code>
	 */
	abstract protected void doRemoveFile(File file);
	
	/**
	 * Called before the watcher is started. Can be used for extenders
	 */
	protected void doStart() {
	}
	
	/**
	 * Called after the watcher stop. Can be used for extenders
	 */
	protected void doStop() {
	}

	/**
	 * Updates a file, by storing it into a map and notifying a implementor 
	 * @param file, the file to update
	 */
	private void updateFile(File file) {
		if (file == null) {
			logger.severe("Cannot update a null file instance");
			return;
		}
		fileMap.put(file.getName(), file);
		doUpdateFile(file);
	}
	
	/**
	 * Removes a file, by removing it from the cache and notifying a implementor 
	 * @param file, the file to remove
	 */
	private void removeFile(String fileName) {
		if (fileName == null) {
			logger.severe("Cannot remove a null file name");
			return;
		}
		File file = fileMap.remove(fileName);
		if (file != null) {
			doRemoveFile(file);
		} else {
			logger.severe(String.format("[%s] No file found to remove", fileName));
		}
	}

	/**
	 * Reads the configuration folder and registers all files in it as service
	 */
	private void readConfigDirectory() {
		if (directory == null) {
			logger.warning("No confioguration folder URL is available, to be read");
			return;
		}
		try {
			File confFolder = new File(directory.toURI());
			if (!confFolder.exists()) {
				logger.info(String.format("[%s] Detected an config folder that does not exist", confFolder.getAbsolutePath()));
				return;
			}
			if (confFolder.isDirectory()) {
				if (!confFolder.canRead()) {
					logger.info(String.format("[%s] Detected an config folder that is not readable. Stop further reading", confFolder.getName()));
					return;
				}
				for (File file : confFolder.listFiles(fileNameFilter)) {
					if (file.isDirectory()) {
						logger.info(String.format("[%s] Detected an folder", file.getName()));
						continue;
					}
					if (file.isHidden()) {
						logger.info(String.format("[%s] Detected an hidden file. It will be ignored", file.getName()));
						continue;
					}
					if (!file.canRead()) {
						logger.info(String.format("[%s] Detected an file with no read rights. It will be ignored", file.getName()));
						continue;
					}
					updateFile(file);
				}
			} else {
				logger.log(Level.SEVERE, String.format("[%s] The URL is expected to be a folder, but is obviously none", GeckoConstants.PROP_GECKO_CONFIG_DIR));
			}
		} catch (URISyntaxException e) {
			logger.log(Level.SEVERE, String.format("[%s] The URL cannot be converted into a file URI", GeckoConstants.PROP_GECKO_CONFIG_DIR), e);
		}
	}

	/**
	 * Closes and cleans up the watcher 
	 */
	private void disposeFolderWatcher() {
		if (fileWatcher != null) {
			try {
				fileWatcher.close();
				fileWatcher = null;
			} catch (IOException e) {
				logger.log(Level.SEVERE, String.format("[%s] The configuration folder watcher cannot be close", GeckoConstants.PROP_GECKO_CONFIG_DIR), e);
			}
		}
	}

	/**
	 * Registers the file watcher for the configuration path 
	 * @return the {@link File} instance
	 * @throws IOException
	 * @throws URISyntaxException
	 */
	private File registerFileWatcher() {
		try {
			if (fileWatcher == null) {
				fileWatcher = FileSystems.getDefault().newWatchService();
			}
			File confFolder = new File(directory.toURI());
			if (!confFolder.exists()) {
				confFolder.mkdirs();
				logger.log(Level.INFO, String.format("[%s] The config folder was created", GeckoConstants.PROP_GECKO_CONFIG_DIR));
			}
			if (!confFolder.isDirectory()) {
				logger.log(Level.SEVERE, String.format("[%s] The URL for the config path is expected to be a folder, but is obviously none", GeckoConstants.PROP_GECKO_CONFIG_DIR));
				return null;
			}
			Path confPath = confFolder.toPath();
			confPath.register(fileWatcher, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE);
			return confFolder;
		} catch (URISyntaxException e) {
			logger.log(Level.SEVERE, String.format("[%s] The URL cannot be converted into a file URI for the watcher service", GeckoConstants.PROP_GECKO_CONFIG_DIR), e);
		} catch (IOException e) {
			logger.log(Level.SEVERE, String.format("[%s] The configuration folder cannot be registered for watching CREATE or REMOVE events", GeckoConstants.PROP_GECKO_CONFIG_DIR), e);
		}
		return null;
	}

}
