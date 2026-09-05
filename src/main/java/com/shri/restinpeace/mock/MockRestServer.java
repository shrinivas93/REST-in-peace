package com.shri.restinpeace.mock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.exception.RestInPeaceException;

/**
 * A real, local HTTP server for unit-testing code that calls a
 * {@code @RestClient} interface, without depending on a real network - point
 * {@link com.shri.restinpeace.RIP#getClient(Class, String)} at
 * {@link #baseUrl()} and this server stands in for the real one.
 *
 * <p>
 * Deliberately a genuine HTTP server (the same {@code com.sun.net.httpserver}
 * this project's own integration test suite already uses), listening on
 * loopback, rather than a fake transport swapped in underneath Unirest - so
 * every request this server receives went through RIP's real request-building
 * and Unirest's real serialization, exactly as it would against a real
 * server. {@code @Retry}, {@code @Timeout}, and every registered
 * {@code RequestInterceptor} all run completely unmodified.
 *
 * <pre>
 * MockRestServer server = MockRestServer.start();
 * server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{\"status\":\"CONFIRMED\"}"));
 *
 * OrderApi api = RIP.getClient(OrderApi.class, server.baseUrl());
 * Order order = api.getOrder("abc123");
 *
 * RecordedRequest sent = server.takeRequest();
 * assertEquals("/orders/abc123", sent.getPath());
 *
 * server.close();
 * </pre>
 */
public final class MockRestServer implements AutoCloseable {

	private final HttpServer server;
	private final Deque<MockResponse> queue = new ArrayDeque<>();
	private final List<Route> routes = new ArrayList<>();
	private final List<RecordedRequest> recorded = Collections.synchronizedList(new ArrayList<>());

	private MockRestServer(HttpServer server) {
		this.server = server;
	}

	/**
	 * Starts a new server on an OS-assigned loopback port.
	 *
	 * @return the started server
	 */
	public static MockRestServer start() {
		try {
			HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			MockRestServer mockServer = new MockRestServer(httpServer);
			httpServer.createContext("/", mockServer::handle);
			httpServer.setExecutor(null);
			httpServer.start();
			return mockServer;
		} catch (IOException e) {
			throw new RestInPeaceException("Failed to start MockRestServer.", e);
		}
	}

	/**
	 * Returns this server's base URL, to pass to
	 * {@link com.shri.restinpeace.RIP#getClient(Class, String)} or
	 * {@link com.shri.restinpeace.RipClientConfig.Builder#baseUrl(String)}.
	 *
	 * @return the base URL, e.g. {@code "http://localhost:54321"}
	 */
	public String baseUrl() {
		return "http://localhost:" + server.getAddress().getPort();
	}

	/**
	 * Queues a response for the next request that doesn't match a route
	 * registered via {@link #on}, consumed in the order requests arrive - the
	 * way to script a sequence of responses to the same endpoint (e.g. a
	 * {@code 503} then a {@code 200}, to prove {@code @Retry} recovers).
	 *
	 * @param response the response to queue
	 * @return this server
	 */
	public MockRestServer enqueue(MockResponse response) {
		synchronized (queue) {
			queue.add(response);
		}
		return this;
	}

	/**
	 * Registers a response for every request matching {@code httpMethod} and
	 * {@code pathTemplate} - unlike {@link #enqueue}, this doesn't get
	 * consumed; it keeps answering every matching request the same way.
	 * {@code pathTemplate} may contain {@code {name}} placeholders (matching
	 * RIP's own path-param syntax), each matching exactly one path segment,
	 * e.g. {@code "/orders/{id}"} matches {@code /orders/abc123} but not
	 * {@code /orders/abc/123}. Checked before the {@link #enqueue} queue, in
	 * the order routes were registered - the first matching route wins.
	 *
	 * @param httpMethod   the HTTP method to match
	 * @param pathTemplate the path to match, with optional {@code {name}}
	 *                     placeholders
	 * @param response     the response to send for every matching request
	 * @return this server
	 */
	public MockRestServer on(HTTPMethod httpMethod, String pathTemplate, MockResponse response) {
		routes.add(new Route(httpMethod, pathTemplate, response));
		return this;
	}

	/**
	 * Removes and returns the oldest recorded request, for asserting against
	 * one call at a time in the order calls were made.
	 *
	 * @return the oldest recorded request
	 * @throws NoSuchElementException if no request has been recorded
	 */
	public RecordedRequest takeRequest() {
		synchronized (recorded) {
			if (recorded.isEmpty()) {
				throw new NoSuchElementException("No request has been recorded.");
			}
			return recorded.remove(0);
		}
	}

	/**
	 * Returns every request recorded so far, oldest first, without consuming
	 * them - unlike {@link #takeRequest()}.
	 *
	 * @return the recorded requests
	 */
	public List<RecordedRequest> getRecordedRequests() {
		synchronized (recorded) {
			return new ArrayList<>(recorded);
		}
	}

	/**
	 * Returns how many requests have been recorded so far.
	 *
	 * @return the recorded request count
	 */
	public int requestCount() {
		return recorded.size();
	}

	/** Stops the server. */
	@Override
	public void close() {
		server.stop(0);
	}

	private void handle(HttpExchange exchange) throws IOException {
		RecordedRequest request = RecordedRequest.capture(exchange);
		recorded.add(request);

		for (Route route : routes) {
			if (route.matches(request)) {
				route.response.writeTo(exchange);
				return;
			}
		}
		MockResponse response;
		synchronized (queue) {
			response = queue.poll();
		}
		if (response == null) {
			response = MockResponse.status(500, "MockRestServer: no response was queued or registered for "
					+ request.getHttpMethod() + " " + request.getPath() + ".");
		}
		response.writeTo(exchange);
	}

	private static final class Route {
		private final HTTPMethod httpMethod;
		private final Pattern pathPattern;
		private final MockResponse response;

		Route(HTTPMethod httpMethod, String pathTemplate, MockResponse response) {
			this.httpMethod = httpMethod;
			this.pathPattern = compile(pathTemplate);
			this.response = response;
		}

		boolean matches(RecordedRequest request) {
			return httpMethod == request.getHttpMethod() && pathPattern.matcher(request.getPath()).matches();
		}

		private static Pattern compile(String pathTemplate) {
			StringBuilder regex = new StringBuilder();
			for (String segment : pathTemplate.split("/", -1)) {
				if (regex.length() > 0) {
					regex.append('/');
				}
				if (segment.startsWith("{") && segment.endsWith("}")) {
					regex.append("[^/]+");
				} else {
					regex.append(Pattern.quote(segment));
				}
			}
			return Pattern.compile(regex.toString());
		}
	}

}
