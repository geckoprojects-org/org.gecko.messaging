/**
 * Copyright (c) 2012 - 2018 Data In Motion and others.
 * All rights reserved. 
 * 
 * This program and the accompanying materials are made available under the terms of the 
 * Eclipse Public License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
package org.gecko.adapter.eventadmin;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.gecko.adapter.eventadmin.context.EventAdminMessagingContext;
import org.gecko.osgi.messaging.Message;
import org.gecko.osgi.messaging.MessagingConstants;
import org.gecko.osgi.messaging.MessagingContext;
import org.gecko.osgi.messaging.MessagingService;
import org.gecko.osgi.messaging.SimpleMessagingContextBuilder;
import org.osgi.annotation.bundle.Capability;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.util.pushstream.PushStream;

@Capability(namespace=MessagingConstants.CAPABILITY_NAMESPACE, name=MessagingConstants.EVENTADMIN_ADAPTER, version=MessagingConstants.EVENTADMIN_ADAPTER_VERSION, attribute= {"vendor=Gecko.io", "implementation=Eventadmin"})
@Component
public class EventAdminMessageService implements MessagingService{

	/** HEADERS */
	public static final String HEADERS_PREFIX = "headers";

	/** CONTENT */
	public static final String CONTENT = "content";

	@Reference
	private EventAdmin eventAdmin;
	
	private static final Logger logger = Logger.getLogger(EventAdminMessageService.class.getName());
	private Map<String, TopicEventHandler> subscribtions = new ConcurrentHashMap<>();

	private BundleContext bundleContext;

	
	@Activate
	public void activate(ComponentContext context, Map<String, Object> properties) {
		bundleContext = context.getBundleContext();
	}
	
	@Deactivate
	public void deactivate() {
		for (Iterator<Entry<String, TopicEventHandler>> iterator = subscribtions.entrySet().iterator(); iterator.hasNext();) {
			Entry<String, TopicEventHandler> entry = iterator.next();
			entry.getValue().deactivate();
			iterator.remove();
		}
	}
	
	/* 
	 * (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingService#subscribe(java.lang.String)
	 */
	@Override
	public PushStream<Message> subscribe(String topic) throws Exception {
		return subscribe(topic, new SimpleMessagingContextBuilder().build());
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingService#subscribe(java.lang.String, org.gecko.osgi.messaging.MessagingContext)
	 */
	@Override
	public PushStream<Message> subscribe(String topic, MessagingContext context) throws Exception {
		
		final String key = generateKey(topic, context);
		TopicEventHandler newSub = new TopicEventHandler(topic, bundleContext, () -> subscribtions.remove(key), context);
		TopicEventHandler sub = subscribtions.putIfAbsent(key, newSub);
		if(sub == null) {
			logger.info("Adding subsciption to " + key);
			sub = newSub;
		}
		PushStream<Message> stream = sub.registerPushStream();
		if(stream == null) {
			return subscribe(topic, context);
		}
		return stream;
	}

	/**
	 * @param topic
	 * @return
	 */
	private String generateKey(String topic, MessagingContext context) {
		return topic;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingService#publish(java.lang.String, java.nio.ByteBuffer)
	 */
	@Override
	public void publish(String topic, ByteBuffer content) throws Exception {
		Event event = new Event(topic, Collections.singletonMap(CONTENT, content));
		eventAdmin.postEvent(event);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingService#publish(java.lang.String, java.nio.ByteBuffer, org.gecko.osgi.messaging.MessagingContext)
	 */
	@Override
	public void publish(String topic, ByteBuffer content, MessagingContext context) throws Exception {
//		Map<String, Object> props = c.convert(context).sourceAsBean().view()
//		           .to(new TypeReference<Map<String,Object>>(){});

		Map<String, Object> props = new HashMap<String, Object>();
		
		translateContextIntoMap(context, props);
		
		props.put(CONTENT, content);		
		
		Event event = new Event(topic, props);
		eventAdmin.postEvent(event);
	}

	/**
	 * @param props
	 * @param context
	 */
	private void translateContextIntoMap(MessagingContext context, Map<String, Object> props) {
		putIfPresent(context::getContentEncoding, "content.encoding", props);
		putIfPresent(context::getContentType, "content.type", props);
		putIfPresent(context::getCorrelationId, "correlation.id", props);
		putIfPresent(context::getQueueName, "queue.name", props);
		putIfPresent(context::getReplyAddress, "reply.address", props);
		putIfPresent(context::getRoutingKey, "routing.key", props);
		if(context instanceof EventAdminMessagingContext) {
			props.put(HEADERS_PREFIX, ((EventAdminMessagingContext) context).getHeaders());
		}
	}
	
	/**
	 * @param object
	 * @param string
	 */
	private void putIfPresent(Supplier<Object> supplier, String key, Map<String, Object> props) {
		Optional.ofNullable(supplier.get()).ifPresent(partial(props::put, key));
	}

	public static  Consumer<Object> partial(BiFunction<String, Object, Object> f, String key) {
        return (o) -> f.apply(key, o);
    }

}
