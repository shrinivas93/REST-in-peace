package com.shri.restinpeace.interceptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A synthetic response a {@link RequestInterceptor#shortCircuit} can hand
 * back to skip the network call entirely - the request is never sent, and
 * this response is decoded exactly as if it had come back from a real one
 * (including throwing {@link com.shri.restinpeace.exception.RestInPeaceHttpException}
 * for a non-2xx status, and being visible to every registered interceptor's
 * own {@code afterResponse}). Enables a feature-flag bypass, a canary
 * short-circuit, or a lightweight record/replay mode built on the
 * interceptor chain.
 *
 * <p>
 * A {@code byte[]}-returning method sees {@link #getBody()} re-encoded as
 * UTF-8 bytes; a {@code File}-returning method has those bytes written to
 * its destination file, same as a real response's would be.
 */
public final class ShortCircuitResponse {

	private final int status;
	private final String body;
	private final Map<String, List<String>> headers = new LinkedHashMap<>();

	private ShortCircuitResponse(int status, String body) {
		this.status = status;
		this.body = body;
	}

	/**
	 * A {@code 200 OK} response with a body.
	 *
	 * @param body the response body
	 * @return the new response
	 */
	public static ShortCircuitResponse ok(String body) {
		return new ShortCircuitResponse(200, body);
	}

	/**
	 * A response with an arbitrary status and body.
	 *
	 * @param status the HTTP status code
	 * @param body   the response body
	 * @return the new response
	 */
	public static ShortCircuitResponse status(int status, String body) {
		return new ShortCircuitResponse(status, body);
	}

	/**
	 * Adds a header value, in addition to any already added under the same
	 * name.
	 *
	 * @param name  the header name
	 * @param value the header value
	 * @return this response, for chaining
	 */
	public ShortCircuitResponse header(String name, String value) {
		headers.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
		return this;
	}

	/**
	 * Returns the response status code.
	 *
	 * @return the status code
	 */
	public int getStatus() {
		return status;
	}

	/**
	 * Returns the response body.
	 *
	 * @return the response body
	 */
	public String getBody() {
		return body;
	}

	/**
	 * Returns the response headers added via {@link #header}.
	 *
	 * @return the headers
	 */
	public Map<String, List<String>> getHeaders() {
		return headers;
	}

}
