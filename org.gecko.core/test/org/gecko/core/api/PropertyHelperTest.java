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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 
 * @author mark
 * @since 20.03.2019
 */
public class PropertyHelperTest {
	
	@AfterEach
	public void after() {
		System.clearProperty("TESTENV");
		System.clearProperty("TESTENV2");
	}

	/**
	 * Test method for {@link org.gecko.core.api.PropertyHelper#getValue(java.util.Map, java.lang.String, java.lang.Object)}.
	 */
	@org.junit.jupiter.api.Test
	public void testGetValueWithDefault() {
		System.setProperty("test", "test");
		PropertyHelper helper = PropertyHelper.createHelper();
		Map<String, Object> props = new HashMap<String, Object>();
		assertEquals("hello", helper.getValue(props, "test", "hello"));
		
		props.put("test", "me");
		assertEquals("me", helper.getValue(props, "test", "hello"));
		
		System.setProperty("TESTENV", "envTESTProp");
		assertEquals("me", helper.getValue(props, "test", "hello"));
		
		props.put("test.env", "TESTENV");
		assertEquals("envTESTProp", helper.getValue(props, "test", "hello"));

		props.put("hello", "world");
		assertEquals("world", helper.getValue(props, "hello", "universe"));
	}

	/**
	 * Test method for {@link org.gecko.core.api.PropertyHelper#getValue(java.util.Map, java.lang.String)}.
	 */
	@Test
	public void testGetValue() {
		PropertyHelper helper = PropertyHelper.createHelper();
		Map<String, Object> props = new HashMap<String, Object>();
		assertNull(helper.getValue(props, "test"));
		
		props.put("test", "me");
		assertEquals("me", helper.getValue(props, "test"));

		System.setProperty("TESTENV2", "envTESTProp2");
		assertEquals("me", helper.getValue(props, "test"));
		props.put("test.env", "TESTENV");
		assertNull(helper.getValue(props, "test"));
		props.put("test.env", "TESTENV2");
		assertEquals("envTESTProp2", helper.getValue(props, "test"));
		
		props.put("hello", "world");
		assertEquals("world", helper.getValue(props, "hello", "universe"));
	}
	
	/**
	 * Test method for {@link org.gecko.core.api.PropertyHelper#getValue(java.util.Map, java.lang.String)}.
	 */
	@Test
	public void testError() {
		System.setProperty("test", "test");
		PropertyHelper helper = PropertyHelper.createHelper();
		Map<String, Object> props = new HashMap<String, Object>();
		assertNull(helper.getValue(null, "test"));
		assertNull(helper.getValue(props, null));
	}

}
