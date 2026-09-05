package com.shri.restinpeace.mock;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;

import kong.unirest.Unirest;
import kong.unirest.UnirestConfigException;

import com.shri.restinpeace.exception.RestInPeaceException;

/**
 * A canned response for {@link MockRestServer} to send back for a matching
 * request.
 */
public final class MockResponse {

	private final int status;
	private final byte[] body;
	private final Map<String, List<String>> headers = new LinkedHashMap<>();
	private final boolean connectionFailure;
	private long delayMillis;

	private MockResponse(int status, byte[] body) {
		this(status, body, false);
	}

	private MockResponse(int status, byte[] body, boolean connectionFailure) {
		this.status = status;
		this.body = body;
		this.connectionFailure = connectionFailure;
	}

	/**
	 * A {@code 200 OK} response with a body, defaulting {@code Content-Type} to
	 * {@code application/json} - override via {@link #header} for a
	 * plain-text/other body.
	 *
	 * @param body the response body
	 * @return the new response
	 */
	public static MockResponse ok(String body) {
		return status(200, body).header("Content-Type", "application/json");
	}

	/**
	 * A {@code 200 OK} response, serializing {@code body} with the same
	 * Unirest {@code ObjectMapper} RIP itself delegates to (the one
	 * {@link com.shri.restinpeace.RIP#setObjectMapper} sets) - so a test can
	 * hand this a plain object instead of hand-writing a JSON string. Uses
	 * the globally-configured mapper specifically; a method under test whose
	 * client was built with a per-client {@code RipClientConfig}'s own
	 * {@code objectMapper(...)} isn't necessarily decoding with the same one
	 * - pre-serialize to a {@code String} and use {@link #ok(String)} instead
	 * in that case.
	 *
	 * @param body the object to serialize as the response body
	 * @return the new response
	 * @throws RestInPeaceException if no {@code ObjectMapper} is configured -
	 *                              call {@link com.shri.restinpeace.RIP#setObjectMapper}
	 *                              first, same as RIP itself requires for JSON
	 *                              (de)serialization
	 */
	public static MockResponse json(Object body) {
		try {
			return ok(Unirest.config().getObjectMapper().writeValue(body));
		} catch (UnirestConfigException e) {
			throw new RestInPeaceException(
					"No JSON ObjectMapper is configured. MockResponse.json(...) delegates to the same "
							+ "Unirest ObjectMapper RIP itself uses - call RIP.setObjectMapper(...) first, or "
							+ "pre-serialize to a String and use MockResponse.ok(String) instead.",
					e);
		}
	}

	/**
	 * A {@code 204 No Content} response.
	 *
	 * @return the new response
	 */
	public static MockResponse noContent() {
		return status(204, "");
	}

	/**
	 * A response with the given status and a raw body, no default headers.
	 *
	 * @param status the HTTP status code
	 * @param body   the response body
	 * @return the new response
	 */
	public static MockResponse status(int status, String body) {
		return new MockResponse(status, body.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * A response with the given status and raw bytes, for a binary
	 * ({@code byte[]}/{@code File}-returning) call.
	 *
	 * @param status the HTTP status code
	 * @param body   the response body
	 * @return the new response
	 */
	public static MockResponse status(int status, byte[] body) {
		return new MockResponse(status, body);
	}

	/**
	 * Simulates a transport-level failure - the connection is closed before
	 * any response is sent, instead of returning an HTTP status - so a test
	 * can exercise RIP's "no response at all" error path (a
	 * {@code kong.unirest.UnirestException} thrown directly, not wrapped in
	 * {@link com.shri.restinpeace.exception.RestInPeaceHttpException}, and
	 * always retried by {@code @Retry} regardless of {@code retryOnStatus})
	 * instead of only ever seeing a non-2xx status. Any {@link #header} or
	 * {@link #delay} set on the returned response is ignored, since no
	 * response is ever sent.
	 *
	 * @return the new response
	 */
	public static MockResponse connectionFailure() {
		return new MockResponse(0, new byte[0], true);
	}

	/**
	 * Adds a response header. Calling this more than once for the same
	 * {@code name} adds an additional value rather than replacing the
	 * previous one - the way HTTP itself allows a header to repeat (e.g.
	 * multiple {@code Set-Cookie} headers on one response) - so send several
	 * values by calling this once per value, in order.
	 *
	 * @param name  the header name
	 * @param value the header value
	 * @return this response
	 */
	public MockResponse header(String name, String value) {
		headers.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
		return this;
	}

	/**
	 * Delays sending this response by {@code delayMillis}, to simulate a
	 * slow server - the way to prove {@code @Timeout(readMillis = ...)} (or
	 * {@code RipClientConfig.readTimeoutMillis(...)}) actually fires,
	 * rather than assuming it does because the annotation is present. The
	 * delay happens before any response bytes are sent, simulating the
	 * server being slow to start responding - exactly what a read timeout
	 * guards against on a connection that's already open, as one to a
	 * loopback {@link MockRestServer} always is.
	 *
	 * @param delayMillis how long to wait before sending this response
	 * @return this response
	 */
	public MockResponse delay(long delayMillis) {
		this.delayMillis = delayMillis;
		return this;
	}

	void writeTo(HttpExchange exchange) throws IOException {
		if (delayMillis > 0) {
			sleep(delayMillis);
		}
		if (connectionFailure) {
			exchange.close();
			return;
		}
		headers.forEach((name, values) -> values.forEach(value -> exchange.getResponseHeaders().add(name, value)));
		exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
		if (body.length > 0) {
			try (OutputStream responseBody = exchange.getResponseBody()) {
				responseBody.write(body);
			}
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RestInPeaceException("Interrupted while simulating a MockResponse delay.", e);
		}
	}

}
