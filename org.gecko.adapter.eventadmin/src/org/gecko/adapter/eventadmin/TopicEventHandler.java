/*
 * Copyright (c) 2012 - 2024 Data In Motion and others.
 * All rights reserved. 
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Data In Motion - initial API and implementation
 */

package org.gecko.adapter.eventadmin;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import org.gecko.adapter.eventadmin.context.EventAdminMessagingContext;
import org.gecko.adapter.eventadmin.context.EventAdminMessagingContextBuilder;
import org.gecko.osgi.messaging.Message;
import org.gecko.osgi.messaging.MessagingContext;
import org.gecko.osgi.messaging.SimpleMessage;
import org.gecko.util.pushstream.PushStreamContext;
import org.gecko.util.pushstream.PushStreamHelper;
import org.gecko.util.pushstream.source.CallBackEventSource;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.PrototypeServiceFactory;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.osgi.util.converter.Converters;
import org.osgi.util.converter.TypeReference;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushEventConsumer;
import org.osgi.util.pushstream.PushStream;
import org.osgi.util.pushstream.PushStreamBuilder;
import org.osgi.util.pushstream.PushStreamProvider;
import org.osgi.util.pushstream.PushbackPolicyOption;
import org.osgi.util.pushstream.QueuePolicyOption;
import org.osgi.util.pushstream.SimplePushEventSource;

/**
 * The actual subscription. This class serves 2 perupses:
 * 1. 	It is used by the message adapter to represent a supscription.
 * 2. 	This class can be configured individually via {@link ConfigurationAdmin}, 
 * 		if done so, it is possible to get PushStreams injected directly.
 * 
 * @author Juergen Albert
 * @since 21 Jan 2019
 */
@Component(name="EventAdminTopicSubscription", service = {}, configurationPolicy = ConfigurationPolicy.REQUIRE)
public class TopicEventHandler implements PrototypeServiceFactory<PushStream<Message>> {

	private static enum State {
		NEW,
		CONNECTED,
		CLOSED,
		DISPOSED
	}
	
	private static final Logger logger = Logger.getLogger(TopicEventHandler.class.getName());
	
	private State state = State.NEW;
	
	private SimplePushEventSource<Message> eventSource;

	private BundleContext ctx;

	private ServiceRegistration<EventHandler> eventHandlerRegistration;

	private Runnable doAfterClose;
	
	private ReentrantLock lock = new ReentrantLock();

	private String topic;
	
	private PushStreamProvider provider = new PushStreamProvider();

	private PushStreamContext<Message> messagingContext;

	private ServiceRegistration<?> pushStreamFactoryServiceRegistration;

	
	@ObjectClassDefinition
	@interface Config {
		String topic();
	}	
	
	public TopicEventHandler() {
		
	}
	
	public TopicEventHandler (String topic, BundleContext bundleContext, Runnable doAfterClose, MessagingContext messagingContext) {
		this();
		this.topic = topic;
		this.ctx = bundleContext;
		this.doAfterClose = doAfterClose;
		this.messagingContext = messagingContext;
		SimplePushEventSource<Message> source = provider.buildSimpleEventSource(Message.class).withQueuePolicy(QueuePolicyOption.BLOCK).build();
		eventSource = new CallBackEventSource<Message>(source, 
				this::openAndConnectIfNecessary, 
				null,
				this::consumerClosed);
	}
	
	/**
	 * Activate method, that registers the component as {@link PrototypeServiceFactory} 
	 */
	@Activate
	public void activate(Config config, Map<String, Object> properties,  ComponentContext componentContext) {
//		this.properties = properties;
		this.topic = config.topic();
		this.ctx = componentContext.getBundleContext();
		this.messagingContext = PushStreamHelper.getPushStreamContext(properties);
		SimplePushEventSource<Message> source = provider.buildSimpleEventSource(Message.class).withQueuePolicy(QueuePolicyOption.BLOCK).build();
		eventSource = new CallBackEventSource<Message>(source, 
				this::openAndConnectIfNecessary, null, null);
		Dictionary<String,Object> dictionary = Converters.standardConverter().convert(properties).to(new TypeReference<Dictionary<String,Object>>(){});
		pushStreamFactoryServiceRegistration = ctx.registerService(PushStream.class.getName(), this, dictionary);
	}
	
	@Deactivate
	public void deactivate() {
		lock.lock();
		try {
			state = State.CLOSED;
			if(pushStreamFactoryServiceRegistration != null) {
				pushStreamFactoryServiceRegistration.unregister();
			}
			if(eventHandlerRegistration != null) {
				eventHandlerRegistration.unregister();
			}
		} finally {
			lock.unlock();
		}
		eventSource.close();
		state = State.DISPOSED;
	}
	
	/* 
	 * (non-Javadoc)
	 * @see org.osgi.service.event.EventHandler#handleEvent(org.osgi.service.event.Event)
	 */
	public void handle(Event event) {
		logger.fine("Handle incomming event " + event.toString());
		String topic = event.getTopic();
		ByteBuffer content = (ByteBuffer) event.getProperty(EventAdminMessageService.CONTENT);
		assert eventSource != null;
		
		Map<String, Object> props = new HashMap<String, Object>();
		Arrays.asList(event.getPropertyNames()).stream().filter(k -> !EventAdminMessageService.CONTENT.equals(k)).forEach(k -> props.put(k, event.getProperty(k)));
		
		EventAdminMessagingContext context = translateMapIntoContext(event);		
		eventSource.publish(new SimpleMessage(topic, content, context));
		
		logger.fine("finished Handing of incomming event " + event.toString());
	}
	
	public void openAndConnectIfNecessary(CallBackEventSource<Message> source, PushEventConsumer<? super Message> consumer) {
		logger.fine("Opening eventSource");
		if(state == State.NEW) {
			lock.lock();
			try {
				if(state == State.NEW) {
					logger.fine("registering Eventhandler for topic " + topic);
					Dictionary<String, Object> props = new Hashtable<String, Object>();
					props.put(EventConstants.EVENT_TOPIC, new String[] {topic});
					eventHandlerRegistration = ctx.registerService(EventHandler.class, new EventHandler() {

						@Override
						public void handleEvent(Event event) {
							handle(event);
						}
						
					}, props);
					state = State.CONNECTED;
				}
			} finally {
				lock.unlock();
			}
		}
	}
	
	public PushStream<Message> registerPushStream(){
		lock.lock();
		try {
			if(state == State.CLOSED || state == State.DISPOSED) {
				return null;
			} 
			PushStreamBuilder<Message, BlockingQueue<PushEvent<? extends Message>>> buildStream = PushStreamHelper
	                .configurePushStreamBuilder(eventSource, messagingContext)
	                .withPushbackPolicy(PushbackPolicyOption.ON_FULL_FIXED, 10);
			if(messagingContext.getBufferQueue() == null) {
				buildStream.withBuffer(new ArrayBlockingQueue<PushEvent<? extends Message>>(messagingContext.getBufferSize() > 0 ? messagingContext.getBufferSize() : 1000));
			}
			return buildStream.build();
		} finally {
			lock.unlock();
		}
	}
	
	private void consumerClosed(CallBackEventSource<Message> eventSource, PushEventConsumer<? super Message> consumer) {
		logger.fine("closing event Source");
		lock.lock();
		try {
			if(state == State.CONNECTED) {
				if(!eventSource.isConnected()) {
					state = State.CLOSED;
					eventHandlerRegistration.unregister();
					eventHandlerRegistration = null;
					if(doAfterClose != null) {
						doAfterClose.run();
					}
					state = State.DISPOSED;
				}
			}
		} finally {
			lock.unlock();
		}
	}
	
	/* 
	 * (non-Javadoc)
	 * @see org.osgi.framework.PrototypeServiceFactory#getService(org.osgi.framework.Bundle, org.osgi.framework.ServiceRegistration)
	 */
	@Override
	public PushStream<Message> getService(Bundle bundle, ServiceRegistration<PushStream<Message>> registration) {
		return registerPushStream();
	}

	/* 
	 * (non-Javadoc)
	 * @see org.osgi.framework.PrototypeServiceFactory#ungetService(org.osgi.framework.Bundle, org.osgi.framework.ServiceRegistration, java.lang.Object)
	 */
	@Override
	public void ungetService(Bundle bundle, ServiceRegistration<PushStream<Message>> registration,
			PushStream<Message> service) {
		service.close();
	}
	
	/**
	 * @param props
	 * @param context
	 */
	@SuppressWarnings("unchecked")
	private EventAdminMessagingContext translateMapIntoContext(Event event) {
		EventAdminMessagingContextBuilder contextBuilder = EventAdminMessagingContextBuilder.builder();
		if(event.containsProperty(EventAdminMessageService.HEADERS_PREFIX)){
			contextBuilder.headers((Map<String, Object>) event.getProperty(EventAdminMessageService.HEADERS_PREFIX));
		}
		
		contextBuilder.contentEncoding((String) event.getProperty("content.encoding"));
		contextBuilder.contentType((String) event.getProperty("content.type"));
		contextBuilder.correlationId((String) event.getProperty("correlation.id"));
		contextBuilder.queue((String) event.getProperty("queue.name"));
		contextBuilder.replyTo((String) event.getProperty("reply.address"));
		
		return (EventAdminMessagingContext) contextBuilder.build();
	}
}
