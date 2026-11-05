/**
 * Copyright (c) 2012 - 2026 Data In Motion and others.
 * All rights reserved.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Data In Motion - initial API and implementation
 */
package org.gecko.messaging.tck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.gecko.messaging.api.MessagingConstants.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.gecko.messaging.api.Message;
import org.gecko.messaging.api.MessageContext;
import org.gecko.messaging.api.replyto.ReplyToPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.util.promise.Promise;

/**
 * The requesting side of reply-to (MSG160), against a responder subscribed in the test.
 */
public class ReplyToTest extends AbstractMessagingTest {

	private ReplyToPublisher replyTo;

	@BeforeEach
	void needsReplyTo() {
		assumeTrue(tck.has(FEATURE_REPLY_TO));
		replyTo = tck.service(ReplyToPublisher.class);
	}

	/** Answers every request on the channel with the given number of responses. */
	private void responder(String channel, int responses, boolean complete) {
		track(subscription.subscribe(channel)).forEach(request -> {
			MessageContext rc = request.getContext();
			for (int i = 1; i <= responses; i++) {
				publisher.publish(response(rc).content(Tck.bytes(Tck.text(request) + "-" + i)).buildMessage());
			}
			if (complete) {
				publisher.publish(response(rc).header(HEADER_REPLY_COMPLETE, true).buildMessage());
			}
		});
	}

	private org.gecko.messaging.api.MessageContextBuilder response(MessageContext request) {
		return builder().channel(request.getReplyToChannel()).correlationId(request.getCorrelationId());
	}

	@Test
	void singleResponseWithOwnCorrelationAndChannel() throws Exception {
		String channel = tck.channel("rpc", "own");
		responder(channel, 1, false);
		MessageContext ctx = builder().channel(channel).correlationId("corr-1").replyTo(tck.channel("rpc", "own", "reply"))
				.buildContext();
		Message response = replyTo.publishWithReply(builder().content(Tck.bytes("ping")).buildMessage(), ctx).getValue();
		assertThat(Tck.text(response)).isEqualTo("ping-1");
		assertThat(response.getContext().getCorrelationId()).isEqualTo("corr-1");
	}

	@Test
	void generatedCorrelationAndReplyChannel() throws Exception {
		assumeTrue(tck.has(FEATURE_GENERATE_CORRELATION_ID) && tck.has(FEATURE_GENERATE_REPLY_CHANNEL));
		String channel = tck.channel("rpc", "generated");
		responder(channel, 1, false);
		Message first = replyTo.publishWithReply(text(channel, "a")).getValue();
		Message second = replyTo.publishWithReply(text(channel, "b")).getValue();
		assertThat(Tck.text(first)).isEqualTo("a-1");
		assertThat(Tck.text(second)).isEqualTo("b-1");
		assertThat(first.getContext().getCorrelationId()).isNotNull().isNotEqualTo(second.getContext().getCorrelationId());
	}

	@Test
	void concurrentRequestsGetTheirOwnResponses() throws Exception {
		assumeTrue(tck.has(FEATURE_GENERATE_CORRELATION_ID) && tck.has(FEATURE_GENERATE_REPLY_CHANNEL));
		String channel = tck.channel("rpc", "concurrent");
		responder(channel, 1, false);
		List<Promise<Message>> promises = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			promises.add(replyTo.publishWithReply(text(channel, "r" + i)));
		}
		for (int i = 0; i < 10; i++) {
			assertThat(Tck.text(promises.get(i).getValue())).isEqualTo("r" + i + "-1");
		}
	}

	@Test
	void noResponseTimesOut() throws Exception {
		String channel = tck.channel("rpc", "silent");
		MessageContext ctx = builder().channel(channel).correlationId("t-1").replyTo(tck.channel("rpc", "silent", "reply"))
				.extensionEntry(EXTENSION_REPLY_TIMEOUT, 300L).buildContext();
		Promise<Message> p = replyTo.publishWithReply(builder().content(Tck.bytes("?")).buildMessage(), ctx);
		assertThat(p.getFailure()).isInstanceOf(TimeoutException.class);
	}

	@Test
	void manyResponsesEndWithTheCompleteHeader() throws Exception {
		assumeTrue(tck.has(FEATURE_REPLY_TO_MANY));
		String channel = tck.channel("rpc", "many");
		responder(channel, 3, true);
		MessageContext ctx = builder().channel(channel).correlationId("many-1").replyTo(tck.channel("rpc", "many", "reply"))
				.buildContext();
		List<String> responses = Collections.synchronizedList(new ArrayList<>());
		replyTo.publishWithReplyMany(builder().content(Tck.bytes("go")).buildMessage(), ctx).map(Tck::text).forEach(responses::add)
				.getValue();
		assertThat(responses).containsExactly("go-1", "go-2", "go-3");
	}

	@Test
	void manyResponsesTimeOutWithoutEnd() throws Exception {
		assumeTrue(tck.has(FEATURE_REPLY_TO_MANY));
		String channel = tck.channel("rpc", "endless");
		responder(channel, 2, false);
		MessageContext ctx = builder().channel(channel).correlationId("endless-1").replyTo(tck.channel("rpc", "endless", "reply"))
				.extensionEntry(EXTENSION_REPLY_TIMEOUT, 500L).buildContext();
		List<String> responses = Collections.synchronizedList(new ArrayList<>());
		Promise<Void> done = replyTo.publishWithReplyMany(builder().content(Tck.bytes("go")).buildMessage(), ctx).map(Tck::text)
				.forEach(responses::add);
		assertThat(done.getFailure()).isInstanceOf(TimeoutException.class);
		assertThat(responses).containsExactly("go-1", "go-2");
	}

}
