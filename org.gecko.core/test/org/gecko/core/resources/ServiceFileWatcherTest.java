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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import org.gecko.core.resources.file.ServiceFileWatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

/**
 * Tests the {@link ConfigurationFileWatcher}
 * @author Mark Hoffmann
 *
 */
@ExtendWith(MockitoExtension.class)
public class ServiceFileWatcherTest {
	
	private File configFolder;
	
	@BeforeEach
	public void before() {
		String tmp = System.getProperty("tmp");
		configFolder = new File(tmp + "/geckoconf");
		if (!configFolder.exists()) {
			configFolder.mkdirs();
		} else {
			for (File f : configFolder.listFiles()) {
				assertTrue(f.delete());
			}
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

	@SuppressWarnings("unchecked")
	@Test
	public void testSimple() throws IOException, InterruptedException {
		BundleContext ctxm = mock(BundleContext.class);
		ServiceRegistration<File> fileReg01 = mock(ServiceRegistration.class);
		when(ctxm.registerService(any(Class.class), any(Object.class), any())).thenReturn(fileReg01);
		
		ServiceFileWatcher watcher = new ServiceFileWatcher(configFolder.toURI().toURL(), ctxm);
		watcher.start();
		Thread.sleep(100);
		File testFile = new File(configFolder, "test_me.txt");
		assertTrue(testFile.createNewFile());
		Thread.sleep(100);
		watcher.stop();
		verify(ctxm, times(1)).registerService(any(Class.class), any(Object.class), any());
		verify(fileReg01, times(1)).unregister();
		verify(fileReg01, times(0)).setProperties(any());
	}
	
	@SuppressWarnings("unchecked")
	@Test
	public void testFilesAlreadExist() throws IOException, InterruptedException {
		File testFile1 = new File(configFolder, "test_me.txt");
		assertTrue(testFile1.createNewFile());
		File testFile2 = new File(configFolder, "test_me2.txt");
		assertTrue(testFile2.createNewFile());
		
		BundleContext ctxm = mock(BundleContext.class);
		ServiceRegistration<File> fileReg01 = mock(ServiceRegistration.class);
		when(ctxm.registerService(any(Class.class), any(Object.class), any())).thenReturn(fileReg01);
		
		ServiceFileWatcher watcher = new ServiceFileWatcher(configFolder.toURI().toURL(), ctxm);
		watcher.start();
		Thread.sleep(1000);
		watcher.stop();
		verify(ctxm, times(2)).registerService(any(Class.class), any(Object.class), any());
		verify(fileReg01, times(2)).unregister();
		verify(fileReg01, times(0)).setProperties(any());
	}
	
	@SuppressWarnings("unchecked")
	@Test
	public void testFilesAlreadExistCreateNew01() throws IOException, InterruptedException {
		File testFile1 = new File(configFolder, "test_me.txt");
		assertTrue(testFile1.createNewFile());
		File testFile2 = new File(configFolder, "test_me2.txt");
		assertTrue(testFile2.createNewFile());
		
		BundleContext ctxm = mock(BundleContext.class);
		ServiceRegistration<File> fileReg01 = mock(ServiceRegistration.class);
		when(ctxm.registerService(any(Class.class), any(Object.class), any())).thenReturn(fileReg01);
		
		ServiceFileWatcher watcher = new ServiceFileWatcher(configFolder.toURI().toURL(), ctxm);
		watcher.start();
		Thread.sleep(100);
		
		File testFile3 = new File(configFolder, "test_me3.txt");
		assertTrue(testFile3.createNewFile());
		
		Thread.sleep(100);
		watcher.stop();
		verify(ctxm, times(3)).registerService(any(Class.class), any(Object.class), any());
		verify(fileReg01, times(3)).unregister();
		verify(fileReg01, times(0)).setProperties(any());
	}
	
	@SuppressWarnings("unchecked")
	@Test
	public void testFilesAlreadExistCreateNew02() throws IOException, InterruptedException {
		File testFile1 = new File(configFolder, "test_me.txt");
		assertTrue(testFile1.createNewFile());
		File testFile2 = new File(configFolder, "test_me2.txt");
		assertTrue(testFile2.createNewFile());
		
		BundleContext ctxm = mock(BundleContext.class);
		ServiceRegistration<File> fileReg01 = mock(ServiceRegistration.class);
		when(ctxm.registerService(any(Class.class), any(Object.class), any())).thenReturn(fileReg01);
		
		ServiceFileWatcher watcher = new ServiceFileWatcher(configFolder.toURI().toURL(), ctxm);
		watcher.start();
		
		File testFile3 = new File(configFolder, "test_me3.txt");
		assertTrue(testFile3.createNewFile());
		
		Thread.sleep(100);
		watcher.stop();
		verify(ctxm, times(3)).registerService(any(Class.class), any(Object.class), any());
		verify(fileReg01, times(3)).unregister();
		verify(fileReg01, times(0)).setProperties(any());
	}
	
	@SuppressWarnings("unchecked")
	@Test
	public void testFilesChangeFile() throws IOException, InterruptedException {
		BundleContext ctxm = mock(BundleContext.class);
		ServiceRegistration<File> fileReg01 = mock(ServiceRegistration.class);
		when(ctxm.registerService(any(Class.class), any(Object.class), any())).thenReturn(fileReg01);
		
		ServiceFileWatcher watcher = new ServiceFileWatcher(configFolder.toURI().toURL(), ctxm);
		watcher.start();
		
		File testFile3 = new File(configFolder, "test_me3.txt");
		assertTrue(testFile3.createNewFile());
		
		Thread.sleep(100);
		
		FileOutputStream fos = new FileOutputStream(testFile3);
		fos.write("test".getBytes());
		fos.flush();
		fos.close();
		
		Thread.sleep(100);
		
		fos = new FileOutputStream(testFile3);
		fos.write("hello world".getBytes());
		fos.flush();
		fos.close();
		
		Thread.sleep(100);
		
		watcher.stop();
		verify(ctxm, times(1)).registerService(any(Class.class), any(Object.class), any());
		verify(fileReg01, times(1)).unregister();
		verify(fileReg01, times(2)).setProperties(any());
	}
	
	@SuppressWarnings("unchecked")
	@Test
	public void testFilesAlreadExistCreateFilter01() throws IOException, InterruptedException {
		File testFile1 = new File(configFolder, "test_me.txt");
		assertTrue(testFile1.createNewFile());
		File testFile2 = new File(configFolder, "test_me2.conf");
		assertTrue(testFile2.createNewFile());
		
		BundleContext ctxm = mock(BundleContext.class);
		ServiceRegistration<File> fileReg01 = mock(ServiceRegistration.class);
		when(ctxm.registerService(any(Class.class), any(Object.class), any())).thenReturn(fileReg01);
		
		ServiceFileWatcher watcher = new ServiceFileWatcher(configFolder.toURI().toURL(), ctxm);
		watcher.setFilterList(new String[] {"*.conf"});
		watcher.start();
		
		File testFile3 = new File(configFolder, "test_me3.txt");
		assertTrue(testFile3.createNewFile());
		
		Thread.sleep(100);
		
		File testFile4 = new File(configFolder, "test_me4.conf");
		assertTrue(testFile4.createNewFile());
		
		Thread.sleep(100);
		
		File testFile5 = new File(configFolder, "test_me5.txt");
		assertTrue(testFile5.createNewFile());
		
		Thread.sleep(100);
		watcher.stop();
		verify(ctxm, times(2)).registerService(any(Class.class), any(Object.class), any());
		verify(fileReg01, times(2)).unregister();
		verify(fileReg01, times(0)).setProperties(any());
	}
	
	@SuppressWarnings("unchecked")
	@Test
	public void testRemoveConfFolder() throws IOException, InterruptedException {
		
		BundleContext ctxm = mock(BundleContext.class);
		ServiceRegistration<File> fileReg01 = mock(ServiceRegistration.class);
		when(ctxm.registerService(any(Class.class), any(Object.class), any())).thenReturn(fileReg01);
		
		ServiceFileWatcher watcher = new ServiceFileWatcher(configFolder.toURI().toURL(), ctxm);
		watcher.start();
		Thread.sleep(100);
		
		File testFile1 = new File(configFolder, "test_me1.txt");
		assertTrue(testFile1.createNewFile());
		Thread.sleep(100);
		
		assertTrue(testFile1.delete());
		Thread.sleep(100);
		assertTrue(configFolder.delete());
		assertFalse(configFolder.exists());
		
		Thread.sleep(100);
		
		assertTrue(configFolder.exists());
		File testFile2 = new File(configFolder, "test_me2.txt");
		assertTrue(testFile2.createNewFile());
		
		Thread.sleep(100);
		
		watcher.stop();
		verify(ctxm, times(2)).registerService(any(Class.class), any(Object.class), any());
		verify(fileReg01, times(2)).unregister();
		verify(fileReg01, times(0)).setProperties(any());
	}
	
	@SuppressWarnings("unchecked")
	@Test
	public void testConfigFolderNotExists() throws IOException, InterruptedException {
		assertTrue(configFolder.delete());
		assertFalse(configFolder.exists());
		
		BundleContext ctxm = mock(BundleContext.class);
		ServiceRegistration<File> fileReg01 = mock(ServiceRegistration.class);
		when(ctxm.registerService(any(Class.class), any(Object.class), any())).thenReturn(fileReg01);
		
		ServiceFileWatcher watcher = new ServiceFileWatcher(configFolder.toURI().toURL(), ctxm);
		watcher.start();
		Thread.sleep(100);
		File testFile = new File(configFolder, "test_me.txt");
		assertTrue(testFile.createNewFile());
		Thread.sleep(100);
		watcher.stop();
		verify(ctxm, times(1)).registerService(any(Class.class), any(Object.class), any());
		verify(fileReg01, times(1)).unregister();
		verify(fileReg01, times(0)).setProperties(any());
	}

}
