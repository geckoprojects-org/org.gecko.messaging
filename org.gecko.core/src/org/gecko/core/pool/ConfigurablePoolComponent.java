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
package org.gecko.core.pool;

import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.component.ComponentServiceObjects;

/**
 * 
 * @author ilenia
 * @since Dec 13, 2019
 */
public class ConfigurablePoolComponent<T> {
	
	private static final Logger logger = Logger.getLogger(ConfigurablePoolComponent.class.getName());
	
	private BundleContext ctx;
	private PoolConfiguration config;
	
	private static final int DEFAULT_POOL_SIZE = 5;
	private static final int DEFAULT_POOL_TIMEOUT = 100;
	public static final String DEFAULT_REF_FILTER = "(pool.name=*)";
	
	private final Map<String, Pool<T>> poolMap = new HashMap<String, Pool<T>>();
	
	private final ConcurrentMap<ComponentServiceObjects<T>, Map<String, Object>> componentServiceObj = new ConcurrentHashMap<ComponentServiceObjects<T>, Map<String,Object>>();
	
	private final Map<String, ServiceRegistration<?>> serviceRegistrationMap  = new HashMap<String, ServiceRegistration<?>>();
	
	public @interface PoolConfiguration {
		String pool_componentName() default "";
		boolean pool_asService() default true;
		int pool_size() default DEFAULT_POOL_SIZE;
		int pool_timeout() default DEFAULT_POOL_TIMEOUT;
	}

	
	public void activate(BundleContext ctx, PoolConfiguration config) throws ConfigurationException {
		this.ctx = ctx;
		if(isConfigOK(config)) {
			this.config = config;
		}	
		registerServiceObjects();
	}
	
	
	private void registerServiceObjects() {
		synchronized (componentServiceObj) {
			for(ComponentServiceObjects<T> serviceObj : componentServiceObj.keySet()) {
				registerPool(serviceObj, componentServiceObj.get(serviceObj));
			}		
		}
	}

	public void deactivate() {
		poolMap.forEach((k, v) -> v.dispose());
		poolMap.clear();
	}
	
	public void registerPool(ComponentServiceObjects<T> serviceObj, Map<String, Object> properties) {		
		if(this.config != null) {
			logger.fine("Registering pool immediately");
			Dictionary<String, Object> combinedProperties = createCombinedProperties(properties);		
			Pool<T> pool = createPool(serviceObj, combinedProperties);
			if((boolean) combinedProperties.get(ConfigurablePoolConstants.POOL_AS_SERVICE)) {
				ServiceRegistration<?> registration = this.ctx.registerService(pool.getClass().getName(), pool, combinedProperties);
				serviceRegistrationMap.put((String) combinedProperties.get(ConfigurablePoolConstants.POOL_COMBINED_ID), registration);
			}		
			poolMap.put((String) combinedProperties.get(ConfigurablePoolConstants.POOL_COMBINED_ID), pool);
		}		
		componentServiceObj.put(serviceObj, properties);
	}	
	
	
	

	public void unregisterPool(ComponentServiceObjects<T> serviceObj) {
		Map<String, Object> properties = componentServiceObj.remove(serviceObj);
		if (properties != null) {
			Dictionary<String, Object> combinedProperties = createCombinedProperties(properties);	
			String combinedId = (String) combinedProperties.get(ConfigurablePoolConstants.POOL_COMBINED_ID);
			logger.fine("Removing tranformator " + combinedId);
			if((boolean) combinedProperties.get(ConfigurablePoolConstants.POOL_AS_SERVICE)) {
				ServiceRegistration<?> registration = serviceRegistrationMap.remove(combinedId);
				if(registration != null) {
					registration.unregister();
				}
			}
			Pool<T> pool = poolMap.get(combinedId);
			if(pool != null) {
				pool.dispose();
				poolMap.remove(combinedId);
			}				
		}
	}
	
	
	public Pool<T> createPool(ComponentServiceObjects<T> serviceObj, Dictionary<String, Object> properties) {
		String poolName = (String) properties.get(ConfigurablePoolConstants.POOL_NAME);
		int poolSize = (int) properties.get(ConfigurablePoolConstants.POOL_SIZE);
		int poolTimeout = (int) properties.get(ConfigurablePoolConstants.POOL_TIMEOUT);
		Pool<T> pool = new Pool<T>(poolName, serviceObj::getService, serviceObj::ungetService, poolSize, poolTimeout);
		pool.initialize();	
		return pool;
	}
	
	public Map<String, Pool<T>> getPoolMap() {
		return this.poolMap;
	}
	
	/**
	 * @param properties
	 * @return
	 */
	private Dictionary<String, Object> createCombinedProperties(Map<String, Object> properties) {
		
		Dictionary<String, Object> combinedProperties = new Hashtable<String, Object>();
		
		combinedProperties.put(ConfigurablePoolConstants.POOL_AS_SERVICE, 
				properties.get(ConfigurablePoolConstants.POOL_AS_SERVICE) != null ? 
						properties.get(ConfigurablePoolConstants.POOL_AS_SERVICE) :
							this.config.pool_asService());
		
		combinedProperties.put(ConfigurablePoolConstants.POOL_SIZE, 
				properties.get(ConfigurablePoolConstants.POOL_SIZE) != null ? 
						properties.get(ConfigurablePoolConstants.POOL_SIZE) :
							this.config.pool_size());
		
		combinedProperties.put(ConfigurablePoolConstants.POOL_TIMEOUT, 
				properties.get(ConfigurablePoolConstants.POOL_TIMEOUT) != null ? 
						properties.get(ConfigurablePoolConstants.POOL_TIMEOUT) :
							this.config.pool_timeout());
		
		combinedProperties.put(ConfigurablePoolConstants.POOL_COMPONENT_NAME, this.config.pool_componentName());
		
		combinedProperties.put(ConfigurablePoolConstants.POOL_NAME, properties.get(ConfigurablePoolConstants.POOL_NAME));
		
		combinedProperties.put(ConfigurablePoolConstants.POOL_COMBINED_ID, 
				createCombinedId((String) properties.get(ConfigurablePoolConstants.POOL_NAME)));				
		
		for(String prop : properties.keySet()) {
			if(!ConfigurablePoolConstants.POOL_AS_SERVICE.equals(prop) &&
					!ConfigurablePoolConstants.POOL_SIZE.equals(prop) &&
					!ConfigurablePoolConstants.POOL_TIMEOUT.equals(prop) && 
					!ConfigurablePoolConstants.POOL_NAME.equals(prop)) {
				combinedProperties.put(prop, properties.get(prop));
			}
		}	
		
		combinedProperties.put("service.factoryPid", "ConfigurablePool");
		return combinedProperties;
	}
	
	private boolean isConfigOK(PoolConfiguration config) throws ConfigurationException {
		if("".equals(config.pool_componentName())) {
			throw new ConfigurationException("pool.componentName", "Cannot activate ConfigurablePoolComponent with no pool.componentName property");
		}		
		return true;
	}
	
	private String createCombinedId(String poolName) {
		return this.config.pool_componentName()+"-"+poolName;
	}
}
