package com.shri.restinpeace.mock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Predicate;
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
	private final List<Route> routes = Collections.synchronizedList(new ArrayList<>());
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
	 * Queues a response for the next request that doesn't match any route
	 * registered via {@link #on} - a route always wins over this queue for a
	 * request it matches, so this only ever answers a path with no matching
	 * route at all. To script a sequence of responses to a path that also
	 * has a sticky final answer (e.g. a {@code 503} then a {@code 200}, to
	 * prove {@code @Retry} recovers, while still returning {@code 200} for
	 * every request after that), register the route with {@link #on} first
	 * and use {@link #enqueueFor} instead - this method can't do that for a
	 * path any route already covers.
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
	 * Calling this again for the same {@code httpMethod} and
	 * {@code pathTemplate} replaces that route's response in place (keeping
	 * its position relative to other routes) instead of adding a second,
	 * permanently-shadowed registration - the way to change one route's
	 * behavior mid-test.
	 *
	 * @param httpMethod   the HTTP method to match
	 * @param pathTemplate the path to match, with optional {@code {name}}
	 *                     placeholders
	 * @param response     the response to send for every matching request
	 * @return this server
	 */
	public MockRestServer on(HTTPMethod httpMethod, String pathTemplate, MockResponse response) {
		return on(httpMethod, pathTemplate, Collections.emptyMap(), response);
	}

	/**
	 * Same as {@link #on(HTTPMethod, String, MockResponse)}, but only matches
	 * a request whose query params contain every entry in
	 * {@code requiredQueryParams} with an equal value - any other query
	 * params the request carries are ignored. Useful for an endpoint that
	 * behaves differently depending on a query param, e.g. registering one
	 * route for {@code ?status=active} and another for
	 * {@code ?status=archived} on the same path. As with the simpler
	 * overload, routes are checked in registration order and the first match
	 * wins - register the more specific (query-constrained) route first if a
	 * less specific one for the same path would otherwise shadow it. Calling
	 * this again with the same {@code httpMethod}, {@code pathTemplate}, and
	 * {@code requiredQueryParams} replaces that route in place, the same as
	 * the simpler overload.
	 *
	 * @param httpMethod          the HTTP method to match
	 * @param pathTemplate        the path to match, with optional
	 *                            {@code {name}} placeholders
	 * @param requiredQueryParams the query params that must be present with
	 *                            these exact values for this route to match
	 * @param response            the response to send for every matching
	 *                            request
	 * @return this server
	 */
	public MockRestServer on(HTTPMethod httpMethod, String pathTemplate, Map<String, String> requiredQueryParams,
			MockResponse response) {
		Route route = new Route(httpMethod, pathTemplate, requiredQueryParams, response);
		synchronized (routes) {
			int existingIndex = indexOfRoute(httpMethod, pathTemplate, requiredQueryParams);
			if (existingIndex >= 0) {
				routes.set(existingIndex, route);
			} else {
				routes.add(route);
			}
		}
		return this;
	}

	/**
	 * Same as {@link #on(HTTPMethod, String, MockResponse)}, but only
	 * matches a request for which {@code matcher} also returns
	 * {@code true} - for matching on a request header or the request body,
	 * neither of which the {@code requiredQueryParams} overload covers,
	 * e.g. {@code request -> "v2".equals(request.getHeader("X-Api-Version"))}
	 * or {@code request -> request.getBody().contains("\"tier\":\"premium\"")}.
	 * As with the other overloads, routes are checked in registration order
	 * and the first match wins - register the more specific (matcher)
	 * route first if a less specific one for the same path would otherwise
	 * shadow it.
	 *
	 * <p>
	 * Unlike the other {@code on(...)} overloads, calling this again never
	 * replaces an earlier registration - two arbitrary {@link Predicate}s
	 * can't be compared for equality the way a {@code requiredQueryParams}
	 * map can, so there's no reliable way to tell whether it's "the same"
	 * route being re-registered. Use {@link #remove} first if that's the
	 * intent.
	 *
	 * @param httpMethod   the HTTP method to match
	 * @param pathTemplate the path to match, with optional {@code {name}}
	 *                     placeholders
	 * @param matcher      an additional condition the request must satisfy
	 * @param response     the response to send for every matching request
	 * @return this server
	 */
	public MockRestServer on(HTTPMethod httpMethod, String pathTemplate, Predicate<RecordedRequest> matcher,
			MockResponse response) {
		routes.add(new Route(httpMethod, pathTemplate, matcher, response));
		return this;
	}

	/**
	 * Scripts a one-time response for a route already registered via
	 * {@link #on(HTTPMethod, String, MockResponse)}, consumed - in the order
	 * queued - before that route's sticky response, the way to combine a
	 * scripted failure sequence with a sticky final answer for the same
	 * path (e.g. a {@code 503} then a {@code 200}, to prove {@code @Retry}
	 * recovers, while every request after that still gets the {@code 200}
	 * the route was registered with). Unlike {@link #enqueue}, which only
	 * ever answers a path with no route at all, this attaches to a specific
	 * route regardless of what other routes are registered.
	 *
	 * @param httpMethod   the HTTP method of the route to script a response
	 *                     for
	 * @param pathTemplate the path template of the route, exactly as passed
	 *                     to {@link #on(HTTPMethod, String, MockResponse)}
	 * @param response     the one-time response to queue
	 * @return this server
	 * @throws NoSuchElementException if no route matching {@code httpMethod}
	 *                                and {@code pathTemplate} has been
	 *                                registered via {@link #on} yet
	 */
	public MockRestServer enqueueFor(HTTPMethod httpMethod, String pathTemplate, MockResponse response) {
		return enqueueFor(httpMethod, pathTemplate, Collections.emptyMap(), response);
	}

	/**
	 * Same as {@link #enqueueFor(HTTPMethod, String, MockResponse)}, for a
	 * route registered via
	 * {@link #on(HTTPMethod, String, Map, MockResponse)} with the same
	 * {@code requiredQueryParams}.
	 *
	 * @param httpMethod          the HTTP method of the route to script a
	 *                            response for
	 * @param pathTemplate        the path template of the route, exactly as
	 *                            passed to {@link #on(HTTPMethod, String,
	 *                            Map, MockResponse)}
	 * @param requiredQueryParams the required query params of the route,
	 *                            exactly as passed to
	 *                            {@link #on(HTTPMethod, String, Map,
	 *                            MockResponse)}
	 * @param response            the one-time response to queue
	 * @return this server
	 * @throws NoSuchElementException if no matching route has been
	 *                                registered via {@link #on} yet
	 */
	public MockRestServer enqueueFor(HTTPMethod httpMethod, String pathTemplate,
			Map<String, String> requiredQueryParams, MockResponse response) {
		synchronized (routes) {
			int index = indexOfRoute(httpMethod, pathTemplate, requiredQueryParams);
			if (index < 0) {
				throw new NoSuchElementException("No route registered via on(...) for " + httpMethod + " "
						+ pathTemplate + " - call on(...) first to give it a sticky response, then enqueueFor(...) "
						+ "to script one-time responses before it.");
			}
			routes.get(index).enqueue(response);
		}
		return this;
	}

	/**
	 * Registers a route that fails {@code failuresBeforeSuccess} times with
	 * {@code failureResponse} before settling into {@code successResponse}
	 * for every request after that - sugar for calling
	 * {@link #on(HTTPMethod, String, MockResponse)} with
	 * {@code successResponse} and then {@link #enqueueFor} with
	 * {@code failureResponse} {@code failuresBeforeSuccess} times.
	 *
	 * @param httpMethod            the HTTP method to match
	 * @param pathTemplate          the path to match, with optional
	 *                              {@code {name}} placeholders
	 * @param failuresBeforeSuccess how many requests get
	 *                              {@code failureResponse} before
	 *                              {@code successResponse} takes over
	 * @param failureResponse       the response sent for the first
	 *                              {@code failuresBeforeSuccess} matching
	 *                              requests
	 * @param successResponse       the response sent for every matching
	 *                              request after that
	 * @return this server
	 */
	public MockRestServer onFlaky(HTTPMethod httpMethod, String pathTemplate, int failuresBeforeSuccess,
			MockResponse failureResponse, MockResponse successResponse) {
		on(httpMethod, pathTemplate, successResponse);
		for (int i = 0; i < failuresBeforeSuccess; i++) {
			enqueueFor(httpMethod, pathTemplate, failureResponse);
		}
		return this;
	}

	/**
	 * Removes a route registered via
	 * {@link #on(HTTPMethod, String, MockResponse)}, so a later request to
	 * that path falls through to any other registered route, the
	 * {@link #enqueue} queue, or the default "no response was queued or
	 * registered" failure - without a full {@link #reset()}, which would
	 * also wipe every other route, the queue, and recorded-request history.
	 * A no-op (returning {@code false}) if no such route is registered.
	 *
	 * @param httpMethod   the HTTP method of the route to remove
	 * @param pathTemplate the path template of the route, exactly as passed
	 *                     to {@link #on(HTTPMethod, String, MockResponse)}
	 * @return {@code true} if a route was removed, {@code false} if none
	 *         matched
	 */
	public boolean remove(HTTPMethod httpMethod, String pathTemplate) {
		return remove(httpMethod, pathTemplate, Collections.emptyMap());
	}

	/**
	 * Same as {@link #remove(HTTPMethod, String)}, for a route registered
	 * via {@link #on(HTTPMethod, String, Map, MockResponse)} with the same
	 * {@code requiredQueryParams}.
	 *
	 * @param httpMethod          the HTTP method of the route to remove
	 * @param pathTemplate        the path template of the route, exactly as
	 *                            passed to {@link #on(HTTPMethod, String,
	 *                            Map, MockResponse)}
	 * @param requiredQueryParams the required query params of the route,
	 *                            exactly as passed to
	 *                            {@link #on(HTTPMethod, String, Map,
	 *                            MockResponse)}
	 * @return {@code true} if a route was removed, {@code false} if none
	 *         matched
	 */
	public boolean remove(HTTPMethod httpMethod, String pathTemplate, Map<String, String> requiredQueryParams) {
		synchronized (routes) {
			int index = indexOfRoute(httpMethod, pathTemplate, requiredQueryParams);
			if (index < 0) {
				return false;
			}
			routes.remove(index);
			return true;
		}
	}

	private int indexOfRoute(HTTPMethod httpMethod, String pathTemplate, Map<String, String> requiredQueryParams) {
		for (int i = 0; i < routes.size(); i++) {
			if (routes.get(i).hasKey(httpMethod, pathTemplate, requiredQueryParams)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Clears every queued response, registered route, and recorded request,
	 * so the same server can be reused across multiple tests instead of
	 * paying to start a new one for each - e.g. from a
	 * {@code @BeforeEach} method, with the server itself started once in a
	 * {@code @BeforeAll}.
	 *
	 * @return this server
	 */
	public MockRestServer reset() {
		synchronized (queue) {
			queue.clear();
		}
		synchronized (routes) {
			routes.clear();
		}
		synchronized (recorded) {
			recorded.clear();
		}
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

		Route matchedRoute = null;
		synchronized (routes) {
			for (Route route : routes) {
				if (route.matches(request)) {
					matchedRoute = route;
					break;
				}
			}
		}
		if (matchedRoute != null) {
			matchedRoute.nextResponse().writeTo(exchange);
			return;
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
		private final String pathTemplate;
		private final Pattern pathPattern;
		private final Map<String, String> requiredQueryParams;
		private final Predicate<RecordedRequest> matcher;
		private final MockResponse response;
		private final Deque<MockResponse> queue = new ArrayDeque<>();

		Route(HTTPMethod httpMethod, String pathTemplate, Map<String, String> requiredQueryParams,
				MockResponse response) {
			this(httpMethod, pathTemplate, requiredQueryParams, null, response);
		}

		Route(HTTPMethod httpMethod, String pathTemplate, Predicate<RecordedRequest> matcher, MockResponse response) {
			this(httpMethod, pathTemplate, Collections.emptyMap(), matcher, response);
		}

		private Route(HTTPMethod httpMethod, String pathTemplate, Map<String, String> requiredQueryParams,
				Predicate<RecordedRequest> matcher, MockResponse response) {
			this.httpMethod = httpMethod;
			this.pathTemplate = pathTemplate;
			this.pathPattern = compile(pathTemplate);
			this.requiredQueryParams = requiredQueryParams;
			this.matcher = matcher;
			this.response = response;
		}

		boolean matches(RecordedRequest request) {
			if (httpMethod != request.getHttpMethod() || !pathPattern.matcher(request.getPath()).matches()) {
				return false;
			}
			for (Map.Entry<String, String> required : requiredQueryParams.entrySet()) {
				if (!required.getValue().equals(request.getQueryParam(required.getKey()))) {
					return false;
				}
			}
			return matcher == null || matcher.test(request);
		}

		boolean hasKey(HTTPMethod httpMethod, String pathTemplate, Map<String, String> requiredQueryParams) {
			return matcher == null && this.httpMethod == httpMethod && this.pathTemplate.equals(pathTemplate)
					&& this.requiredQueryParams.equals(requiredQueryParams);
		}

		void enqueue(MockResponse response) {
			synchronized (queue) {
				queue.add(response);
			}
		}

		MockResponse nextResponse() {
			synchronized (queue) {
				MockResponse queued = queue.poll();
				return queued != null ? queued : response;
			}
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
