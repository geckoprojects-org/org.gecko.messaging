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

import org.junit.jupiter.api.Test;

/**
 * 
 * @author jalbert
 * @since 24 Jan 2019
 */
public class OptionSimplePushEventSourceContextTest {

	@Test
	public void testQueuePolicyOptionWithName01() {
		Map<String, Object> options = new HashMap<>();
		options.put(PushStreamConstants.PROP_SES_QUEUE_POLICY_BY_NAME, "FAIL");
		
		SimplePushEventSourceContext<String> context = new OptionSimpleEventSourceContext<>(options);
		
		assertNotNull(context.getQueuePolicyByName());
	}
	
	@Test
	public void testQueuePolicyOptionWithName02() {
		Map<String, Object> options = new HashMap<>();
		options.put(PushStreamConstants.PROP_SES_QUEUE_POLICY_BY_NAME, "GRADUAL_BREAKING_POLICY");
		
		SimplePushEventSourceContext<String> context = new OptionSimpleEventSourceContext<>(options);
		
		assertNotNull(context.getQueuePolicyByName());
	}
	
	@Test
	public void testQueuePolicyWithNameParameters() {
		Map<String, Object> options = new HashMap<>();
		options.put(PushStreamConstants.PROP_SES_QUEUE_POLICY_BY_NAME, "GRADUAL_BREAKING_POLICY_60_500_5");
		SimplePushEventSourceContext<String> context = new OptionSimpleEventSourceContext<>(options);
		assertNotNull(context.getQueuePolicyByName());
		
		options.clear();
		options.put(PushStreamConstants.PROP_SES_QUEUE_POLICY_BY_NAME, "GRADUAL_BREAKING_POLICY_60_500");
		context = new OptionSimpleEventSourceContext<>(options);
		assertNotNull(context.getQueuePolicyByName());
		
		options.clear();
		options.put(PushStreamConstants.PROP_SES_QUEUE_POLICY_BY_NAME, "GRADUAL_BREAKING_POLICY_60");
		context = new OptionSimpleEventSourceContext<>(options);
		assertNotNull(context.getQueuePolicyByName());
	}
	
	@Test
	public void testQueuePolicyWithNameParametersFail01() {
		Map<String, Object> options = new HashMap<>();
		options.put(PushStreamConstants.PROP_SES_QUEUE_POLICY_BY_NAME, "GRADUAL_BREAKING_POLICY_test_500_5");
		SimplePushEventSourceContext<String> context = new OptionSimpleEventSourceContext<>(options);
		assertThrows(IllegalStateException.class, ()-> context.getQueuePolicyByName());
	}
	
	@Test
	public void testQueuePolicyWithNameParametersFail02() {
		Map<String, Object> options = new HashMap<>();
		options.put(PushStreamConstants.PROP_SES_QUEUE_POLICY_BY_NAME, "GRADUAL_BREAKING_POLICY_60_me");
		SimplePushEventSourceContext<String> context = new OptionSimpleEventSourceContext<>(options);
		assertThrows(IllegalStateException.class, ()-> context.getQueuePolicyByName());
		
	}
	
	@Test
	public void testQueuePolicyWithNameParametersFail() {
		Map<String, Object> options = new HashMap<>();
		options.put(PushStreamConstants.PROP_SES_QUEUE_POLICY_BY_NAME, "GRADUAL_BREAKING_POLICY_blu");
		SimplePushEventSourceContext<String> context = new OptionSimpleEventSourceContext<>(options);
		assertThrows(IllegalStateException.class, ()-> context.getQueuePolicyByName());
	}
	
	@Test
	public void testQueuePolicyWithNameParametersFailNull() {
		Map<String, Object> options = new HashMap<>();
		options.put(PushStreamConstants.PROP_QUEUE_POLICY_BY_NAME, "GRADUAL_BREAKING_POLICY_blu");
		SimplePushEventSourceContext<String> context = new OptionSimpleEventSourceContext<>(options);
		assertNull(context.getQueuePolicyByName());
	}

}
