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
package org.gecko.core.resources.json;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gecko.core.resources.file.AbstractFileWatcher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * FileWatcher that reads Json files. It publishes the changes to the corresponding consumers.
 * @author Mark Hoffmann
 * @since 12.02.2019
 */
public class JsonFileWatcher extends AbstractFileWatcher {
	
	private static final Logger logger = Logger.getLogger(JsonFileWatcher.class.getName());
	private ObjectMapper mapper = new ObjectMapper();
	// consumer to handle updates
	private BiConsumer<String, Map<String, Object>> updateConsumer;
	// consumer to handle removals
	private BiConsumer<String, Map<String, Object>> removeConsumer;
	private Map<String, Map<String, Object>> propertyMaps = new ConcurrentHashMap<String, Map<String,Object>>();

	/**
	 * Creates a new instance. It predefines the filter list to just json - files
	 * @param directoryUrl
	 */
	public JsonFileWatcher(URL directoryUrl) {
		super(directoryUrl, "json");
		setFilterList(new String[] {"*.json"});
	}
	
	/* 
	 * (non-Javadoc)
	 * @see org.gecko.core.resources.file.AbstractFileWatcher#doUpdateFile(java.io.File)
	 */
	@SuppressWarnings("unchecked")
	@Override
	protected void doUpdateFile(File file) {
		if (file == null) {
			logger.warning("Cannot update properties for a null file");
			return;
		}
		try {
			Map<String, Object> jsonMap = mapper.readValue(file, HashMap.class);
			if (jsonMap != null) {
				synchronized (propertyMaps) {
					propertyMaps.put(file.getName(), jsonMap);
				}
				if (updateConsumer != null) {
					jsonMap.entrySet().stream().filter(e->e.getValue() instanceof Map).forEach(e->updateConsumer.accept(e.getKey(), (Map<String, Object>)e.getValue()));
				}
			}
		} catch (JsonProcessingException e) {
			logger.log(Level.SEVERE, String.format("[%s] Error processing Json from the file", file.getName()), e);
		} catch (IOException e) {
			logger.log(Level.SEVERE, String.format("[%s] Error reading Json file", file.getName()), e);
		}
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.core.resources.file.AbstractFileWatcher#doRemoveFile(java.io.File)
	 */
	@SuppressWarnings("unchecked")
	@Override
	protected void doRemoveFile(File file) {
		if (file == null) {
			logger.warning("Cannot remove properties for a null file");
			return;
		}
		Map<String, Object> jsonMap;
		synchronized (propertyMaps) {
			jsonMap = propertyMaps.remove(file.getName());
		}
		if (jsonMap != null && 
				!jsonMap.isEmpty() && 
				removeConsumer != null) {
				jsonMap.entrySet().stream().filter(e->e.getValue() instanceof Map).forEach(e->removeConsumer.accept(e.getKey(), (Map<String, Object>)e.getValue()));
				jsonMap.clear();
		}
	}
	
	public JsonFileWatcher withUpdateCallback(BiConsumer<String, Map<String, Object>> consumer) {
		this.updateConsumer = consumer;
		return this;
	}
	
	public JsonFileWatcher withRemoveCallback(BiConsumer<String, Map<String, Object>> consumer) {
		this.removeConsumer = consumer;
		return this;
	}

}
