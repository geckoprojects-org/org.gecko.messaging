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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.gecko.core.resources.json.JsonFileWatcher;
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
public class JsonFileWatcherTest {
	
	private File configFolder;
	private String jsonConfig1 = "{\n" + 
			"  \":configurator:resource-version\": 1,\n" + 
			"\n" + 
			"  	\"DeviceSampleData\": \n" + 
			"  	{\n" + 
			"    	\"number.devices\": 2000,\n" + 
			"    	\"number.status\": 2000 \n" + 
			"	}\n" + 
			"}";
	private String jsonConfig2 = "{\n" + 
			"  \":configurator:resource-version\": 1,\n" + 
			"\n" + 
			"  	\"DeviceSampleData\": \n" + 
			"  	{\n" + 
			"    	\"number.devices\": 2000,\n" + 
			"    	\"number.status\": 2000 \n" + 
			"	},\n" + 
			"  	\"OtherStuff\": \n" + 
			"  	{\n" + 
			"    	\"other\": 1000,\n" + 
			"    	\"stuff\": \"stuff-value\",\n" + 
			"    	\"todo\": true \n" + 
			"	}\n" + 
			"}";
	private String jsonConfig3 = "{\n" + 
			"  \":configurator:resource-version\": 1,\n" + 
			"\n" + 
			"  	\"Test\": \n" + 
			"  	{\n" + 
			"    	\"test1\": 3000,\n" + 
			"    	\"test2\": 4000 \n" + 
			"	},\n" + 
			"  	\"TestNew\": \n" + 
			"  	{\n" + 
			"    	\"testnew1\": 5000,\n" + 
			"    	\"testnew2\": \"test-value\",\n" + 
			"    	\"testnew3\": false \n" + 
			"	}\n" + 
			"}";
	
	@BeforeEach
	public void before() {
		String tmp = System.getProperty("java.io.tmpdir");
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

	@Test
	public void testNoJson() throws IOException, InterruptedException {
		JsonFileWatcher watcher = new JsonFileWatcher(configFolder.toURI().toURL());
		AtomicInteger addCount = new AtomicInteger();
		AtomicInteger remCount = new AtomicInteger();
		watcher.withUpdateCallback((s,p)->addCount.incrementAndGet()).withRemoveCallback((s,p)->remCount.incrementAndGet());
		watcher.start();
		Thread.sleep(100);
		File testFile = new File(configFolder, "test_me.txt");
		assertTrue(testFile.createNewFile());
		Thread.sleep(100);
		assertEquals(0, addCount.get());
		assertEquals(0, remCount.get());
		watcher.stop();
	}
	
	@Test
	public void testJsonAlreadyExist01() throws IOException, InterruptedException {
		File testFile1 = new File(configFolder, "test_me.json");
		assertTrue(testFile1.createNewFile());
		File testFile2 = new File(configFolder, "test_me2.txt");
		assertTrue(testFile2.createNewFile());
		
		FileOutputStream fos = new FileOutputStream(testFile1);
		fos.write(jsonConfig1.getBytes());
		fos.flush();
		fos.close();
		
		JsonFileWatcher watcher = new JsonFileWatcher(configFolder.toURI().toURL());
		AtomicInteger addCount = new AtomicInteger();
		AtomicInteger remCount = new AtomicInteger();
		watcher
			.withUpdateCallback((s,p)->addCount.incrementAndGet())
			.withRemoveCallback((s,p)->remCount.incrementAndGet());
		assertEquals(0, addCount.get());
		assertEquals(0, remCount.get());
		watcher.start();
		Thread.sleep(100);
		assertEquals(1, addCount.get());
		assertEquals(0, remCount.get());
		watcher.stop();
		Thread.sleep(100);
		assertEquals(1, addCount.get());
		assertEquals(1, remCount.get());
	}
	
	@Test
	public void testJsonAlreadyExist02() throws IOException, InterruptedException {
		File testFile1 = new File(configFolder, "test_me.json");
		assertTrue(testFile1.createNewFile());
		File testFile2 = new File(configFolder, "test_me2.txt");
		assertTrue(testFile2.createNewFile());
		
		FileOutputStream fos = new FileOutputStream(testFile1);
		fos.write(jsonConfig2.getBytes());
		fos.flush();
		fos.close();
		
		JsonFileWatcher watcher = new JsonFileWatcher(configFolder.toURI().toURL());
		AtomicInteger addCount = new AtomicInteger();
		AtomicInteger remCount = new AtomicInteger();
		watcher
		.withUpdateCallback((s,p)->addCount.incrementAndGet())
		.withRemoveCallback((s,p)->remCount.incrementAndGet());
		
		assertEquals(0, addCount.get());
		assertEquals(0, remCount.get());
		watcher.start();
		Thread.sleep(100);
		assertEquals(2, addCount.get());
		assertEquals(0, remCount.get());
		watcher.stop();
		Thread.sleep(100);
		assertEquals(2, addCount.get());
		assertEquals(2, remCount.get());
	}
	
	@Test
	public void testJsonChangeFile() throws IOException, InterruptedException {
		File testFile1 = new File(configFolder, "test_me.json");
		assertTrue(testFile1.createNewFile());
		
		FileOutputStream fos = new FileOutputStream(testFile1);
		fos.write(jsonConfig1.getBytes());
		fos.flush();
		fos.close();
		
		JsonFileWatcher watcher = new JsonFileWatcher(configFolder.toURI().toURL());
		AtomicInteger addCount = new AtomicInteger();
		AtomicInteger remCount = new AtomicInteger();
		watcher
			.withUpdateCallback((s,p)->addCount.incrementAndGet())
			.withRemoveCallback((s,p)->remCount.incrementAndGet());
		
		assertEquals(0, addCount.get());
		assertEquals(0, remCount.get());
		watcher.start();
		
		Thread.sleep(100);
		
		assertEquals(1, addCount.get());
		assertEquals(0, remCount.get());
		
		Thread.sleep(100);
		
		fos = new FileOutputStream(testFile1);
		fos.write(jsonConfig2.getBytes());
		fos.flush();
		fos.close();
		
		Thread.sleep(100);
		
		assertEquals(3, addCount.get());
		assertEquals(0, remCount.get());
		
		watcher.stop();
		
		assertEquals(3, addCount.get());
		assertEquals(2, remCount.get());
	}
	
	@Test
	public void testJsonChangeWithContent() throws IOException, InterruptedException {
		File testFile1 = new File(configFolder, "test_me.json");
		assertTrue(testFile1.createNewFile());
		
		FileOutputStream fos = new FileOutputStream(testFile1);
		fos.write(jsonConfig1.getBytes());
		fos.flush();
		fos.close();
		
		JsonFileWatcher watcher = new JsonFileWatcher(configFolder.toURI().toURL());
		watcher
			.withUpdateCallback(this::testUpdateProperties)
			.withRemoveCallback(this::testUpdateProperties);
		
		watcher.start();
		
		Thread.sleep(100);
		
		fos = new FileOutputStream(testFile1);
		fos.write(jsonConfig2.getBytes());
		fos.flush();
		fos.close();
		
		Thread.sleep(100);
		
		watcher.stop();
		
	}
	
	@Test
	public void testJsonChangeWithContentManyFiles() throws IOException, InterruptedException {
		File testFile1 = new File(configFolder, "test_me.json");
		assertTrue(testFile1.createNewFile());
		
		FileOutputStream fos = new FileOutputStream(testFile1);
		fos.write(jsonConfig1.getBytes());
		fos.flush();
		fos.close();
		
		JsonFileWatcher watcher = new JsonFileWatcher(configFolder.toURI().toURL());
		watcher
		.withUpdateCallback(this::testUpdatePropertiesManyFiles)
		.withRemoveCallback(this::testUpdatePropertiesManyFiles);
		
		watcher.start();
		
		Thread.sleep(100);
		
		fos = new FileOutputStream(testFile1);
		fos.write(jsonConfig2.getBytes());
		fos.flush();
		fos.close();
		
		Thread.sleep(100);
		
		File testFile2 = new File(configFolder, "test_me2.json");
		assertTrue(testFile2.createNewFile());
		fos = new FileOutputStream(testFile2);
		fos.write(jsonConfig3.getBytes());
		fos.flush();
		fos.close();
		
		Thread.sleep(100);
		
		watcher.stop();
		
	}
	
	private void testUpdateProperties(String key, Map<String, Object> properties) {
		switch (key) {
		case "DeviceSampleData":
			assertEquals(2, properties.size());
			assertEquals(2000, properties.get("number.devices"));	
			assertEquals(2000, properties.get("number.status"));	
			break;
		case "OtherStuff":
			assertEquals(3, properties.size());
			assertEquals(1000, properties.get("other"));	
			assertEquals("stuff-value", properties.get("stuff"));	
			assertEquals(Boolean.TRUE.booleanValue(), properties.get("todo"));	
			break;
		default:
			fail("Unknown key " + key);
			break;
		}
	}
	
	private void testUpdatePropertiesManyFiles(String key, Map<String, Object> properties) {
		switch (key) {
		case "Test":
			assertEquals(2, properties.size());
			assertEquals(3000, properties.get("test1"));	
			assertEquals(4000, properties.get("test2"));	
			break;
		case "TestNew":
			assertEquals(3, properties.size());
			assertEquals(5000, properties.get("testnew1"));	
			assertEquals("test-value", properties.get("testnew2"));	
			assertEquals(Boolean.FALSE.booleanValue(), properties.get("testnew3"));	
			break;
		case "DeviceSampleData":
			assertEquals(2, properties.size());
			assertEquals(2000, properties.get("number.devices"));	
			assertEquals(2000, properties.get("number.status"));	
			break;
		case "OtherStuff":
			assertEquals(3, properties.size());
			assertEquals(1000, properties.get("other"));	
			assertEquals("stuff-value", properties.get("stuff"));	
			assertEquals(Boolean.TRUE.booleanValue(), properties.get("todo"));	
			break;
		default:
			fail("Unknown key " + key);
			break;
		}
	}
	
}
