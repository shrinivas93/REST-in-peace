package com.shri.restinpeace.mock;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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
	private final Map<String, String> headers = new LinkedHashMap<>();

	private MockResponse(int status, byte[] body) {
		this.status = status;
		this.body = body;
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
	 * Adds a response header.
	 *
	 * @param name  the header name
	 * @param value the header value
	 * @return this response
	 */
	public MockResponse header(String name, String value) {
		headers.put(name, value);
		return this;
	}

	void writeTo(HttpExchange exchange) throws IOException {
		headers.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
		exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
		if (body.length > 0) {
			try (OutputStream responseBody = exchange.getResponseBody()) {
				responseBody.write(body);
			}
		}
	}

}
