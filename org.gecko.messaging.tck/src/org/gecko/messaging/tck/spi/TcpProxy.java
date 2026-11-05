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
package org.gecko.messaging.tck.spi;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.osgi.framework.BundleContext;

/**
 * A {@link BrokerControl} for TCK runs against a real broker: a TCP proxy between the instances
 * under test and the broker. The instances connect to the proxy, so the TCK can drop their
 * connections or make the broker unreachable without touching the broker itself.
 * <p>
 * A run project registers a subclass as immediate component (so the proxy listens before the
 * first test asks for the broker control), calls {@link #start} on activation and {@link #stop}
 * on deactivation. The framework properties {@code org.gecko.messaging.tck.proxy.listen} (port)
 * and {@code org.gecko.messaging.tck.proxy.broker} ({@code host:port}) override the defaults the
 * run project passes.
 */
public abstract class TcpProxy implements BrokerControl {

	private static final Logger LOG = Logger.getLogger(TcpProxy.class.getName());
	private static final String PREFIX = "org.gecko.messaging.tck.proxy.";

	private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
	private int listenPort;
	private String brokerHost;
	private int brokerPort;
	private ServerSocket server;

	/**
	 * Starts listening.
	 *
	 * @param context the bundle context, for the framework properties
	 * @param defaultListenPort the port of the proxy
	 * @param defaultBroker {@code host:port} of the broker
	 * @throws IOException if the port cannot be bound
	 */
	protected void start(BundleContext context, int defaultListenPort, String defaultBroker) throws IOException {
		String listen = context.getProperty(PREFIX + "listen");
		listenPort = listen == null ? defaultListenPort : Integer.parseInt(listen);
		String broker = context.getProperty(PREFIX + "broker");
		broker = broker == null ? defaultBroker : broker;
		int colon = broker.lastIndexOf(':');
		brokerHost = broker.substring(0, colon);
		brokerPort = Integer.parseInt(broker.substring(colon + 1));
		open();
	}

	/** Stops listening and drops every connection. */
	protected synchronized void stop() {
		closeServer();
		dropConnections();
	}

	@Override
	public void dropConnections() {
		for (Socket s : sockets) {
			close(s);
		}
	}

	@Override
	public synchronized void setAvailable(boolean available) {
		if (available) {
			try {
				open();
			} catch (IOException e) {
				throw new IllegalStateException("The proxy cannot listen on port " + listenPort + " again", e);
			}
		} else {
			closeServer();
			dropConnections();
		}
	}

	private synchronized void open() throws IOException {
		if (server != null) {
			return;
		}
		ServerSocket s = new ServerSocket();
		s.setReuseAddress(true);
		s.bind(new InetSocketAddress("localhost", listenPort));
		server = s;
		Thread.ofVirtual().name("tck-proxy-accept").start(() -> accept(s));
	}

	private synchronized void closeServer() {
		if (server != null) {
			close(server);
			server = null;
		}
	}

	private void accept(ServerSocket s) {
		while (!s.isClosed()) {
			Socket client;
			try {
				client = s.accept();
			} catch (IOException e) {
				return; // closed
			}
			Socket broker;
			try {
				broker = new Socket(brokerHost, brokerPort);
			} catch (IOException e) {
				LOG.log(Level.WARNING, "The proxy cannot reach the broker " + brokerHost + ":" + brokerPort, e);
				close(client);
				continue;
			}
			sockets.add(client);
			sockets.add(broker);
			Thread.ofVirtual().name("tck-proxy-up").start(() -> pump(client, broker));
			Thread.ofVirtual().name("tck-proxy-down").start(() -> pump(broker, client));
		}
	}

	/** Copies from one socket to the other; when either side ends, both are closed. */
	private void pump(Socket from, Socket to) {
		try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
			in.transferTo(out);
		} catch (IOException e) {
			// a dropped or closed connection
		} finally {
			close(from);
			close(to);
			sockets.remove(from);
			sockets.remove(to);
		}
	}

	private static void close(AutoCloseable c) {
		try {
			c.close();
		} catch (Exception e) {
			// closing anyway
		}
	}

}
