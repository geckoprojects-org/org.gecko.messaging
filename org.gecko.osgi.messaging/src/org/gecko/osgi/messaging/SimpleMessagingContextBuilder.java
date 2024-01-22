/**
 * 
 */
package org.gecko.osgi.messaging;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;

import org.gecko.core.pushstream.PushStreamContext;
import org.osgi.util.function.Consumer;
import org.osgi.util.function.Predicate;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushbackPolicy;

/**
 * SimpleMessagingContextBuilder that implements base information
 * @author Mark Hoffmann
 *
 */
public class SimpleMessagingContextBuilder implements MessagingContextBuilder {

	private String queueName;
	private String routingKey;
	private String contentType = null;
	private String contentEncoding = null;
	private String replyTo = null;
	private String correlationId = null;
	private int bufferSize = 0;
	private int parallelism = 1;
	private ExecutorService executor = null;
	private ScheduledExecutorService scheduler = null;
	private BlockingQueue<PushEvent<? extends Message>> bufferQueue = null;
	private Predicate<Message> ackFilter;
	private Consumer<Message> ackFunction;
	private Consumer<Message> nackFunction;
	private BiConsumer<Throwable, Message> ackErrorFunction;
	private PushbackPolicy<Message, BlockingQueue<PushEvent<? extends Message>>> pushbackPolicy;
	private PushStreamContext<Message> pushstreamContext;

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContextBuilder#build()
	 */
	@Override
	public MessagingContext build() {
		return buildContext(new SimpleMessagingContext());
	}

	public static MessagingContextBuilder builder() {
		return new SimpleMessagingContextBuilder();
	}

	protected <T extends SimpleMessagingContext> T buildContext(T ctx) {
		if (bufferSize > 0) {
			ctx.setBufferSize(bufferSize);
		}
		if (parallelism > 1) {
			ctx.setParallelism(parallelism);
		}
		if (executor != null) {
			ctx.setExecutor(executor);
		}
		if (scheduler != null) {
			ctx.setScheduler(scheduler);
		}
		if (bufferQueue != null) {
			ctx.setBufferQueue(bufferQueue);
		}
		if (contentEncoding != null) {
			ctx.setContentEncoding(contentEncoding);
		}
		if (contentType != null) {
			ctx.setContentType(contentType);
		}
		if (correlationId != null) {
			ctx.setCorrelationId(correlationId);
		}
		if (queueName != null) {
			ctx.setQueueName(queueName);
		}
		if (routingKey != null) {
			ctx.setRoutingKey(routingKey);
		}
		if (replyTo != null) {
			ctx.setReplyAddress(replyTo);
		}
		if (ackFilter != null) {
			ctx.setAckFilter(ackFilter);
		}
		if (ackErrorFunction != null) {
			ctx.setAckErrorFunction(ackErrorFunction);
		}
		if (ackFunction != null) {
			ctx.setAckFunction(ackFunction);
		}
		if (nackFunction != null) {
			ctx.setNAckFunction(nackFunction);
		}
		if(pushbackPolicy != null) {
			ctx.setPushbackPolicy(pushbackPolicy);
		}
		if (pushstreamContext != null) {
			ctx.setBufferSize(pushstreamContext.getBufferSize());
			ctx.setExecutor(pushstreamContext.getExecutor());
			ctx.setParallelism(pushstreamContext.getParallelism());
			ctx.setPushbackPolicy(pushstreamContext.getPushbackPolicy());
			ctx.setPushbackPolicyOption(pushstreamContext.getPushbackPolicyOption());
			ctx.setPushbackPolicyOptionTime(pushstreamContext.getPushbackPolicyOptionTime());
			ctx.setQueuePolicy(pushstreamContext.getQueuePolicy());
			ctx.setQueuePolicyOption(pushstreamContext.getQueuePolicyOption());
			ctx.setBufferQueue(pushstreamContext.getBufferQueue());
		}
		return ctx;
	}

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContextBuilder#withBuffer(int)
	 */
	@Override
	public MessagingContextBuilder withBuffer(int size) {
		if (bufferSize > 0) {
			this.bufferSize = size;
		}
		return this;
	}

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContextBuilder#withBufferQueue(java.util.concurrent.BlockingQueue)
	 */
	@Override
	public MessagingContextBuilder withBufferQueue(BlockingQueue<PushEvent<? extends Message>> bufferQueue) {
		if (bufferQueue != null) {
			this.bufferQueue = bufferQueue;
		}
		return this;
	}

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContextBuilder#withBufferQueue(java.util.concurrent.BlockingQueue)
	 */
	@Override
	public MessagingContextBuilder withPushbackPolicy(PushbackPolicy<Message, BlockingQueue<PushEvent<? extends Message>>> pushbackPolicy) {
		if (pushbackPolicy != null) {
			this.pushbackPolicy = pushbackPolicy;
		}
		return this;
	}

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContextBuilder#withParallelism(int)
	 */
	@Override
	public MessagingContextBuilder withParallelism(int parallelism) {
		if (parallelism > 1) {
			this.parallelism = parallelism;
		}
		return this;
	}

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContextBuilder#withExecutor(java.util.concurrent.ExecutorService)
	 */
	@Override
	public MessagingContextBuilder withExecutor(ExecutorService executor) {
		if (executor != null) {
			this.executor = executor;
		}
		return this;
	}

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContextBuilder#withScheduler(java.util.concurrent.ScheduledExecutorService)
	 */
	@Override
	public MessagingContextBuilder withScheduler(ScheduledExecutorService scheduler) {
		if (scheduler != null) {
			this.scheduler = scheduler;
		}
		return this;
	}

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContextBuilder#queue(java.lang.String)
	 */
	@Override
	public MessagingContextBuilder queue(String queueName) {
		this.queueName = queueName;
		this.routingKey = null;
		return this;
	}

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContextBuilder#exchange(java.lang.String, java.lang.String)
	 */
	@Override
	public MessagingContextBuilder exchange(String exchangeName, String routingKey) {
		this.queueName = exchangeName;
		this.routingKey = routingKey;
		return this;
	}

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContextBuilder#contentType(java.lang.String)
	 */
	@Override
	public MessagingContextBuilder contentType(String contentType) {
		if (contentType != null) {
			this.contentType = contentType;
		}
		return this;
	}

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContextBuilder#contentEncoding(java.lang.String)
	 */
	@Override
	public MessagingContextBuilder contentEncoding(String contentEncoding) {
		if (contentEncoding != null) {
			this.contentEncoding = contentEncoding;
		}
		return this;
	}

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContextBuilder#correlationId(java.lang.String)
	 */
	@Override
	public MessagingContextBuilder correlationId(String correlationId) {
		if (correlationId != null) {
			this.correlationId = correlationId;
		}
		return this;
	}

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContextBuilder#replyTo(java.lang.String)
	 */
	@Override
	public MessagingContextBuilder replyTo(String replyToAddress) {
		if (replyToAddress != null) {
			this.replyTo = replyToAddress;
		}
		return this;
	}

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContextBuilder#acknowledgeFilter(org.osgi.util.function.Predicate)
	 */
	@Override
	public MessagingContextBuilder acknowledgeFilter(Predicate<Message> ackFilter) {
		if (ackFilter != null) {
			this.ackFilter = ackFilter;
		}
		return this;
	}

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContextBuilder#acknowledgeFunction(org.osgi.util.function.Consumer)
	 */
	@Override
	public MessagingContextBuilder acknowledgeFunction(Consumer<Message> ackFunction) {
		if (ackFunction != null) {
			this.ackFunction = ackFunction;
		}
		return this;
	}

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContextBuilder#negativeAcknowledgeFunction(org.osgi.util.function.Consumer)
	 */
	public MessagingContextBuilder negativeAcknowledgeFunction(Consumer<Message> nackFunction) {
		if (nackFunction != null) {
			this.nackFunction = nackFunction;
		}
		return this;
	}

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContextBuilder#acknowledgeErrorFunction(java.util.function.BiConsumer)
	 */
	@Override
	public MessagingContextBuilder acknowledgeErrorFunction(BiConsumer<Throwable, Message> ackErrorFunction) {
		if (ackErrorFunction != null) {
			this.ackErrorFunction = ackErrorFunction;
		}
		return this;
	}

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContextBuilder#withPushstreamContext(org.gecko.util.pushstreams.PushStreamContext)
	 */
	@Override
	public MessagingContextBuilder withPushstreamContext(PushStreamContext<Message> context) {
		if (context != null) {
			this.pushstreamContext = context;
		}
		return this;
	}

}
