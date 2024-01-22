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
package org.gecko.core.resources;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FilenameFilter;

import org.gecko.core.resources.file.BasicFilenameFilter;
import org.junit.jupiter.api.Test;

/**
 * @author mark
 *
 */
public class BasicFilenameFilterTest {

	@Test
	public void testNull() {
		FilenameFilter fnf = new BasicFilenameFilter(null);
		assertTrue(fnf.accept(null, "test.txt"));
		fnf = new BasicFilenameFilter(new String[0]);
		assertTrue(fnf.accept(null, "test.txt"));
	}
	
	@Test
	public void testSingle() {
		FilenameFilter fnf = new BasicFilenameFilter(new String[] {"hallo.doc"});
		assertFalse(fnf.accept(null, "test.txt"));
		
		fnf = new BasicFilenameFilter(new String[] {"test.txt"});
		assertTrue(fnf.accept(null, "test.txt"));
	}
	
	@Test
	public void testStartsWith() {
		FilenameFilter fnf = new BasicFilenameFilter(new String[] {"hallo*"});
		assertFalse(fnf.accept(null, "test.txt"));
		assertTrue(fnf.accept(null, "hallo_welt.doc"));
	}
	
	@Test
	public void testEndsWith() {
		FilenameFilter fnf = new BasicFilenameFilter(new String[] {"*.txt"});
		assertFalse(fnf.accept(null, "hallo_welt.doc"));
		assertTrue(fnf.accept(null, "test.txt"));
	}
	
	@Test
	public void testContains() {
		FilenameFilter fnf = new BasicFilenameFilter(new String[] {"*welt*"});
		assertTrue(fnf.accept(null, "hallo_welt.doc"));
		assertFalse(fnf.accept(null, "test.txt"));
	}
	
	@Test
	public void testEquals() {
		FilenameFilter fnf = new BasicFilenameFilter(new String[] {"test.txt"});
		assertFalse(fnf.accept(null, "hallo_welt.doc"));
		assertTrue(fnf.accept(null, "test.txt"));
	}
	
	@Test
	public void testMulti() {
		FilenameFilter fnf = new BasicFilenameFilter(new String[] {"test.txt", "*.properties", "mytest*", "*welt*"});
		assertTrue(fnf.accept(null, "hallo_welt.doc"));
		assertTrue(fnf.accept(null, "test.txt"));
		assertTrue(fnf.accept(null, "mytest.properties"));
		assertTrue(fnf.accept(null, "mytest2.properties"));
		assertTrue(fnf.accept(null, "_mytest2.properties"));
		assertTrue(fnf.accept(null, "config.properties"));
		
		assertFalse(fnf.accept(null, "test.props"));
	}

}
