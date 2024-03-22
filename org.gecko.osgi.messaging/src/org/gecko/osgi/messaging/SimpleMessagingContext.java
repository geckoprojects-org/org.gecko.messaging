/**
 * 
 */
package org.gecko.osgi.messaging;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;

import org.osgi.util.function.Consumer;
import org.osgi.util.function.Predicate;
import org.osgi.util.pushstream.PushEvent;
import org.osgi.util.pushstream.PushbackPolicy;
import org.osgi.util.pushstream.PushbackPolicyOption;
import org.osgi.util.pushstream.QueuePolicy;
import org.osgi.util.pushstream.QueuePolicyOption;

/**
 * Default implementation for a messaging context
 * @author Mark Hoffmann
 *
 */
public class SimpleMessagingContext implements MessagingContext {
	
	private String id;
	private String source;
	private String queueName;
	private String routingKey;
	private String contentType = null;
	private String contentEncoding = null;
	private String replyTo = null;
	private ReplyToPolicy replyToPolicy = ReplyToPolicy.SINGLE;
	private String correlationId = null;
	private int bufferSize = -1;
	private int parallelism = 1;
	private ExecutorService executor = null;
	private ScheduledExecutorService scheduler = null;
	private BlockingQueue<PushEvent<? extends Message>> bufferQueue = null;
	private PushbackPolicy<Message, BlockingQueue<PushEvent<? extends Message>>> pushbackPolicy;
	private PushbackPolicyOption pushbackPolicyOption;
	private Long pushbackPolicyOptionTime;
	private QueuePolicyOption queuePolicyOption;
	private QueuePolicy<Message, BlockingQueue<PushEvent<? extends Message>>> queuePolicy;
	private Predicate<Message> ackFilter;
	private BiConsumer<Throwable, Message> ackErrorFunction;
	private Consumer<Message> ackFunction;
	private Consumer<Message> nackFunction;

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContext#getBufferSize()
	 */
	@Override
	public int getBufferSize() {
		return bufferSize;
	}
	
	void setBufferSize(int bufferSize) {
		if (bufferSize > 0) {
		this.bufferSize = bufferSize;
		}
	}

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContext#getParallelism()
	 */
	@Override
	public int getParallelism() {
		return parallelism;
	}
	
	void setParallelism(int parallelism) {
		if (parallelism > 0) {
		this.parallelism = parallelism;
		}
	}

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContext#getExecutor()
	 */
	@Override
	public ExecutorService getExecutor() {
		return executor;
	}
	
	void setExecutor(ExecutorService executor) {
		if (executor != null) {
		this.executor = executor;
		}
	}

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContext#getScheduler()
	 */
	@Override
	public ScheduledExecutorService getScheduler() {
		return scheduler;
	}
	
	void setScheduler(ScheduledExecutorService scheduler) {
		if (scheduler != null) {
		this.scheduler = scheduler;
		}
	}

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContext#getBufferQueue()
	 */
	@Override
	public BlockingQueue<PushEvent<? extends Message>> getBufferQueue() {
		return bufferQueue;
	}
	
	void setBufferQueue(BlockingQueue<PushEvent<? extends Message>> bufferQueue) {
		if (bufferQueue != null) {
		this.bufferQueue = bufferQueue;
		}
	}

	/* (non-Javadoc)
	 * @see org.gecko.util.pushstreams.PushStreamContext#getQueuePolicy()
	 */
	@Override
	public QueuePolicy<Message, BlockingQueue<PushEvent<? extends Message>>> getQueuePolicy() {
		return queuePolicy;
	}
	
	void setQueuePolicy(QueuePolicy<Message, BlockingQueue<PushEvent<? extends Message>>> policy) {
		if (policy != null) {
			this.queuePolicy = policy;
		}
	}

	/* (non-Javadoc)
	 * @see org.gecko.util.pushstreams.PushStreamContext#getQueuePolicyOption()
	 */
	@Override
	public QueuePolicyOption getQueuePolicyOption() {
		return queuePolicyOption;
	}
	
	void setQueuePolicyOption(QueuePolicyOption policyOption) {
		if (policyOption != null) {
			this.queuePolicyOption = policyOption;
		}
	}

	/* (non-Javadoc)
	 * @see org.gecko.util.pushstreams.PushStreamContext#getPushbackPolicy()
	 */
	@Override
	public PushbackPolicy<Message, BlockingQueue<PushEvent<? extends Message>>> getPushbackPolicy() {
		return pushbackPolicy;
	}

	void setPushbackPolicy(PushbackPolicy<Message, BlockingQueue<PushEvent<? extends Message>>> policy) {
		if (policy != null) {
			this.pushbackPolicy = policy;
		}
	}

	/* (non-Javadoc)
	 * @see org.gecko.util.pushstreams.PushStreamContext#getPushbackPolicyOption()
	 */
	@Override
	public PushbackPolicyOption getPushbackPolicyOption() {
		return pushbackPolicyOption;
	}

	void setPushbackPolicyOption(PushbackPolicyOption policyOption) {
		if (policyOption != null) {
			this.pushbackPolicyOption = policyOption;
		}
	}

	/* (non-Javadoc)
	 * @see org.gecko.util.pushstreams.PushStreamContext#getPushbackPolicyOptionTime()
	 */
	@Override
	public Long getPushbackPolicyOptionTime() {
		return pushbackPolicyOptionTime;
	}

	void setPushbackPolicyOptionTime(Long time) {
		if (time != null) {
			this.pushbackPolicyOptionTime = time;
		}
	}

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContext#getContentType()
	 */
	public String getContentType() {
		return contentType;
	}

	/**
	 * Sets the contentType.
	 * @param contentType the contentType to set
	 */
	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContext#getReplyAddress()
	 */
	public String getReplyAddress() {
		return replyTo;
	}

	/**
	 * Sets the replyTo.
	 * @param replyTo the replyTo to set
	 */
	public void setReplyAddress(String replyTo) {
		this.replyTo = replyTo;
	}

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContext#getCorrelationId()
	 */
	public String getCorrelationId() {
		return correlationId;
	}

	/**
	 * Sets the correlationId.
	 * @param correlationId the correlationId to set
	 */
	public void setCorrelationId(String correlationId) {
		this.correlationId = correlationId;
	}
	
	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContext#getQueueName()
	 */
	public String getQueueName() {
		return queueName;
	}
	
	/**
	 * Sets the queueName.
	 * @param queueName the queueName to set
	 */
	public void setQueueName(String queueName) {
		this.queueName = queueName;
	}
	
	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContext#getRoutingKey()
	 */
	public String getRoutingKey() {
		return routingKey;
	}

	/**
	 * Sets the routingKey.
	 * @param routingKey the routingKey to set
	 */
	public void setRoutingKey(String routingKey) {
		this.routingKey = routingKey;
	}
	
	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContext#getContentEncoding()
	 */
	public String getContentEncoding() {
		return contentEncoding;
	}

	/**
	 * Sets the contentEncoding.
	 * @param contentEncoding the contentEncoding to set
	 */
	public void setContentEncoding(String contentEncoding) {
		this.contentEncoding = contentEncoding;
	}
	
	/* (non-Javadoc)
	 * @see org.gecko.util.pushstreams.PushStreamContext#getAcknowledgeErrorFunction()
	 */
	@Override
	public BiConsumer<Throwable, Message> getAcknowledgeErrorFunction() {
		return ackErrorFunction;
	}
	
	void setAckErrorFunction(BiConsumer<Throwable, Message> ackErrorFunction) {
		this.ackErrorFunction = ackErrorFunction;
	}
	
	/* (non-Javadoc)
	 * @see org.gecko.util.pushstreams.PushStreamContext#getAcknowledgeFilter()
	 */
	@Override
	public Predicate<Message> getAcknowledgeFilter() {
		return ackFilter;
	}
	
	void setAckFilter(Predicate<Message> ackFilter) {
		this.ackFilter = ackFilter;
	}
	
	/* (non-Javadoc)
	 * @see org.gecko.util.pushstreams.PushStreamContext#getAcknowledgeFunction()
	 */
	@Override
	public Consumer<Message> getAcknowledgeFunction() {
		return ackFunction;
	}
	
	void setAckFunction(Consumer<Message> ackFunction) {
		this.ackFunction = ackFunction;
	}
	
	/* (non-Javadoc)
	 * @see org.gecko.util.pushstreams.PushStreamContext#getNAcknowledgeFunction()
	 */
	@Override
	public Consumer<Message> getNAcknowledgeFunction() {
		return nackFunction;
	}
	
	void setNAckFunction(Consumer<Message> nackFunction) {
		this.nackFunction = nackFunction;
	}

	/* (non-Javadoc)
	 * @see org.gecko.util.pushstreams.PushStreamContext#getPushbackPolicyByName()
	 */
	@SuppressWarnings("unchecked")
	@Override
	public PushbackPolicy<Message, BlockingQueue<PushEvent<? extends Message>>> getPushbackPolicyByName() {
		return getPushbackPolicy();
	}

	/* (non-Javadoc)
	 * @see org.gecko.util.pushstreams.PushStreamContext#getQueuePolicyByName()
	 */
	@Override
	public QueuePolicy<Message, BlockingQueue<PushEvent<? extends Message>>> getQueuePolicyByName() {
		return getQueuePolicy();
	}

	/* (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContext#getReplyPolicy()
	 */
	@Override
	public ReplyToPolicy getReplyPolicy() {
		return replyToPolicy;
	}
	
	public void setReplyToPolicy(ReplyToPolicy replyToPolicy) {
		this.replyToPolicy = replyToPolicy;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContext#getSoure()
	 */
	@Override
	public String getSoure() {
		return source;
	}
	
	/**
	 * Sets the source.
	 * @param source the source to set
	 */
	void setSource(String source) {
		this.source = source;
	}

	/* 
	 * (non-Javadoc)
	 * @see org.gecko.osgi.messaging.MessagingContext#getId()
	 */
	@Override
	public String getId() {
		return id;
	}
	
	/**
	 * Sets the id.
	 * @param id the id to set
	 */
	void setId(String id) {
		this.id = id;
	}

}
