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
package org.gecko.core.pushstream;


import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

import org.gecko.core.pushstream.policy.GeckoPushbackPolicyOption;
import org.junit.jupiter.api.Test;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushbackPolicy;
import org.osgi.util.pushstream.PushbackPolicyOption;

/**
 * 
 * @author jalbert
 * @since 24 Jan 2019
 */
public class OptionPushStreamContextTest {

	@Test
	public void testPushbackOptionLINEAR() {
		Map<String, Object> options = new HashMap<>();
		options.put(PushStreamConstants.PROP_PUSHBACK_POLICY_OPTION_BY_NAME, PushbackPolicyOption.LINEAR.name());
		options.put(PushStreamConstants.PROP_PUSHBACK_POLICY_TIME, 10L);
		
		PushStreamContext<String> context = new OptionPushStreamContext<>(options);
		
		PushbackPolicy<String, BlockingQueue<PushEvent<? extends String>>> policyByName = context.getPushbackPolicyByName();
		assertNotNull(policyByName);
	}

	@Test
	public void testPushbackOptionLINEAR_AFTER_THRESHOLD() {
		Map<String, Object> options = new HashMap<>();
		options.put(PushStreamConstants.PROP_PUSHBACK_POLICY_OPTION_BY_NAME, GeckoPushbackPolicyOption.LINEAR_AFTER_THRESHOLD.name());
		options.put(PushStreamConstants.PROP_PUSHBACK_POLICY_TIME, 10L);
		
		PushStreamContext<String> context = new OptionPushStreamContext<>(options);
		
		PushbackPolicy<String, BlockingQueue<PushEvent<? extends String>>> policyByName = context.getPushbackPolicyByName();
		assertNotNull(policyByName);
	}

	@Test
	public void testPushbackOptionNoTimeException() {
		Map<String, Object> options = new HashMap<>();
		options.put(PushStreamConstants.PROP_PUSHBACK_POLICY_OPTION_BY_NAME, GeckoPushbackPolicyOption.LINEAR_AFTER_THRESHOLD.name());
//		options.put(PushStreamConstants.PROP_PUSHBACK_POLICY_TIME, 10L);
		
		PushStreamContext<String> context = new OptionPushStreamContext<>(options);
		
		assertThrows(IllegalArgumentException.class, ()-> context.getPushbackPolicyByName());
	}

	@Test
	public void testPushbackOptionNoPolicyOptionWithName() {
		Map<String, Object> options = new HashMap<>();
		options.put(PushStreamConstants.PROP_PUSHBACK_POLICY_OPTION_BY_NAME, "SOMETHING");
		options.put(PushStreamConstants.PROP_PUSHBACK_POLICY_TIME, 10L);
		
		PushStreamContext<String> context = new OptionPushStreamContext<>(options);
		
		assertThrows(IllegalArgumentException.class, ()-> context.getPushbackPolicyByName());
	}
	
	@Test
	public void testQueuePolicyOptionWithName01() {
		Map<String, Object> options = new HashMap<>();
		options.put(PushStreamConstants.PROP_QUEUE_POLICY_BY_NAME, "FAIL");
		
		PushStreamContext<String> context = new OptionPushStreamContext<>(options);
		
		assertNotNull(context.getQueuePolicyByName());
	}
	
	@Test
	public void testQueuePolicyOptionWithName02() {
		Map<String, Object> options = new HashMap<>();
		options.put(PushStreamConstants.PROP_QUEUE_POLICY_BY_NAME, "GRADUAL_BREAKING_POLICY");
		
		PushStreamContext<String> context = new OptionPushStreamContext<>(options);
		
		assertNotNull(context.getQueuePolicyByName());
	}
	
	@Test
	public void testQueuePolicyWithNameParameters() {
		Map<String, Object> options = new HashMap<>();
		options.put(PushStreamConstants.PROP_QUEUE_POLICY_BY_NAME, "GRADUAL_BREAKING_POLICY_60_500_5");
		PushStreamContext<String> context = new OptionPushStreamContext<>(options);
		assertNotNull(context.getQueuePolicyByName());
		
		options.clear();
		options.put(PushStreamConstants.PROP_QUEUE_POLICY_BY_NAME, "GRADUAL_BREAKING_POLICY_60_500");
		context = new OptionPushStreamContext<>(options);
		assertNotNull(context.getQueuePolicyByName());
		
		options.clear();
		options.put(PushStreamConstants.PROP_QUEUE_POLICY_BY_NAME, "GRADUAL_BREAKING_POLICY_60");
		context = new OptionPushStreamContext<>(options);
		assertNotNull(context.getQueuePolicyByName());
	}
	
	@Test
	public void testQueuePolicyWithNameParametersFail01() {
		Map<String, Object> options = new HashMap<>();
		options.put(PushStreamConstants.PROP_QUEUE_POLICY_BY_NAME, "GRADUAL_BREAKING_POLICY_test_500_5");
		PushStreamContext<String> context = new OptionPushStreamContext<>(options);
		assertThrows(IllegalStateException.class, ()-> context.getQueuePolicyByName());
	}
	
	@Test
	public void testQueuePolicyWithNameParametersFail02() {
		Map<String, Object> options = new HashMap<>();
		options.put(PushStreamConstants.PROP_QUEUE_POLICY_BY_NAME, "GRADUAL_BREAKING_POLICY_60_me");
		PushStreamContext<String> context = new OptionPushStreamContext<>(options);
		assertThrows(IllegalStateException.class, ()-> context.getQueuePolicyByName());
		
	}
	
	@Test
	public void testQueuePolicyWithNameParametersFail() {
		Map<String, Object> options = new HashMap<>();
		options.put(PushStreamConstants.PROP_QUEUE_POLICY_BY_NAME, "GRADUAL_BREAKING_POLICY_blu");
		PushStreamContext<String> context = new OptionPushStreamContext<>(options);
		assertThrows(IllegalStateException.class, ()-> context.getQueuePolicyByName());
	}
	
	@Test
	public void testQueuePolicyWithNameParametersFailNull() {
		Map<String, Object> options = new HashMap<>();
		options.put(PushStreamConstants.PROP_SES_QUEUE_POLICY_BY_NAME, "GRADUAL_BREAKING_POLICY_blu");
		PushStreamContext<String> context = new OptionPushStreamContext<>(options);
		assertNull(context.getQueuePolicyByName());
	}

}
