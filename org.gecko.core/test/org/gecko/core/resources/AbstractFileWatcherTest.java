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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests the {@link ConfigurationFileWatcher}
 * @author Mark Hoffmann
 *
 */
@ExtendWith(MockitoExtension.class)
public class AbstractFileWatcherTest {
	
	private File configFolder;
	
	@BeforeEach
	public void before() {
		String tmp = System.getProperty("java.io.tmpdir");
		configFolder = new File(tmp + "/geckoconf");
		if (!configFolder.exists()) {
			configFolder.mkdirs();
		}
		assertTrue(configFolder.exists());
	}
	
	@AfterEach
	public void after() {
		assertTrue(configFolder.exists());
		if (configFolder.listFiles().length > 0) {
			for (File f : configFolder.listFiles()) {
				assertTrue(f.delete());
			}
		}
		assertTrue(configFolder.delete());
	}

	@Test
	public void testSimple() throws IOException, InterruptedException {
		DummyFileWatcher watcher = new DummyFileWatcher(configFolder.toURI().toURL());
		watcher.start();
		Thread.sleep(1000);
		File testFile = new File(configFolder, "test_me.txt");
		assertTrue(testFile.createNewFile());
		Thread.sleep(1000);
		assertNull(watcher.removedFile.get());
		assertEquals(testFile, watcher.addedFile.get());
		watcher.stop();
		Thread.sleep(1000);
		assertEquals(testFile, watcher.removedFile.get());
	}
	
	@Test
	public void testFilesAlreadyExist() throws IOException, InterruptedException {
		File testFile1 = new File(configFolder, "test_me.txt");
		assertTrue(testFile1.createNewFile());
		File testFile2 = new File(configFolder, "test_me2.txt");
		assertTrue(testFile2.createNewFile());
		
		DummyFileWatcher watcher = new DummyFileWatcher(configFolder.toURI().toURL());
		watcher.start();
		Thread.sleep(1000);
		watcher.stop();
		Thread.sleep(1000);
		assertEquals(2, watcher.addCount);
		assertEquals(2, watcher.removeCount);
	}
	
	@Test
	public void testFilesAlreadyExistCreateNew01() throws IOException, InterruptedException {
		File testFile1 = new File(configFolder, "test_me.txt");
		assertTrue(testFile1.createNewFile());
		File testFile2 = new File(configFolder, "test_me2.txt");
		assertTrue(testFile2.createNewFile());
		
		DummyFileWatcher watcher = new DummyFileWatcher(configFolder.toURI().toURL());
		watcher.start();
		Thread.sleep(1000);
		
		File testFile3 = new File(configFolder, "test_me3.txt");
		assertTrue(testFile3.createNewFile());
		
		Thread.sleep(1000);
		watcher.stop();
	}
	
	@Test
	public void testFilesAlreadyExistCreateNew02() throws IOException, InterruptedException {
		File testFile1 = new File(configFolder, "test_me.txt");
		assertTrue(testFile1.createNewFile());
		File testFile2 = new File(configFolder, "test_me2.txt");
		assertTrue(testFile2.createNewFile());
		
		DummyFileWatcher watcher = new DummyFileWatcher(configFolder.toURI().toURL());
		watcher.start();
		
		File testFile3 = new File(configFolder, "test_me3.txt");
		assertTrue(testFile3.createNewFile());
		
		Thread.sleep(1000);
		watcher.stop();
	}
	
	@Test
	public void testFilesChangeFile() throws IOException, InterruptedException {
		
		DummyFileWatcher watcher = new DummyFileWatcher(configFolder.toURI().toURL());
		watcher.start();
		
		File testFile3 = new File(configFolder, "test_me3.txt");
		assertTrue(testFile3.createNewFile());
		
		Thread.sleep(1000);
		
		FileOutputStream fos = new FileOutputStream(testFile3);
		fos.write("test".getBytes());
		fos.flush();
		fos.close();
		
		Thread.sleep(1000);
		
		fos = new FileOutputStream(testFile3);
		fos.write("hello world".getBytes());
		fos.flush();
		fos.close();
		
		Thread.sleep(1000);
		
		watcher.stop();
	}
	
	@Test
	public void testFilesAlreadExistCreateFilter01() throws IOException, InterruptedException {
		File testFile1 = new File(configFolder, "test_me.txt");
		assertTrue(testFile1.createNewFile());
		File testFile2 = new File(configFolder, "test_me2.conf");
		assertTrue(testFile2.createNewFile());
		
		DummyFileWatcher watcher = new DummyFileWatcher(configFolder.toURI().toURL());
		watcher.setFilterList(new String[] {"*.conf"});
		watcher.start();
		
		File testFile3 = new File(configFolder, "test_me3.txt");
		assertTrue(testFile3.createNewFile());
		
		Thread.sleep(1000);
		
		File testFile4 = new File(configFolder, "test_me4.conf");
		assertTrue(testFile4.createNewFile());
		
		Thread.sleep(1000);
		
		File testFile5 = new File(configFolder, "test_me5.txt");
		assertTrue(testFile5.createNewFile());
		
		Thread.sleep(1000);
		watcher.stop();
	}
	
	@Test
	public void testRemoveConfFolder() throws IOException, InterruptedException {
		
		
		DummyFileWatcher watcher = new DummyFileWatcher(configFolder.toURI().toURL());
		watcher.start();
		Thread.sleep(1000);
		
		File testFile1 = new File(configFolder, "test_me1.txt");
		assertTrue(testFile1.createNewFile());
		Thread.sleep(1000);
		
		assertTrue(testFile1.delete());
		Thread.sleep(100);
		assertTrue(configFolder.delete());
		assertFalse(configFolder.exists());
		
		Thread.sleep(1000);
		
		assertTrue(configFolder.exists());
		File testFile2 = new File(configFolder, "test_me2.txt");
		assertTrue(testFile2.createNewFile());
		
		Thread.sleep(1000);
		
		watcher.stop();
	}
	
	@Test
	public void testConfigFolderNotExists() throws IOException, InterruptedException {
		assertTrue(configFolder.delete());
		assertFalse(configFolder.exists());
		
		DummyFileWatcher watcher = new DummyFileWatcher(configFolder.toURI().toURL());
		watcher.start();
		Thread.sleep(1000);
		File testFile = new File(configFolder, "test_me.txt");
		assertTrue(testFile.createNewFile());
		Thread.sleep(1000);
		watcher.stop();
	}

}
