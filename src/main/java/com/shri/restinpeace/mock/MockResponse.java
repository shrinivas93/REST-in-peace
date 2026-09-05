package com.shri.restinpeace.mock;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;

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
