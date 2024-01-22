package org.gecko.osgi.messaging;

import java.util.Map;

public enum ReplyToPolicy {
		
		SINGLE,
		MULTIPLE;

		public static ReplyToPolicy getPolicy(Map<String, Object> properties) {
			if (properties == null) {
				return SINGLE;
			}
			Object ov = properties.get(MessagingContext.PROP_REPLY_TO_POLICY);
			String v = ov.toString();
			try {
				return valueOf(v);
			} catch (Exception e) {
				return SINGLE;
			}
		}
	}