package com.shri.restinpeace;

import java.util.List;
import java.util.Map;

/**
 * Wraps a successful response's decoded body together with its status code
 * and headers, for a method that needs more than just the body. Declare it
 * as a method's return type (or {@code CompletableFuture<RipResponse<T>>}
 * for an async method) instead of {@code T} directly:
 *
 * <pre>
 * {@literal @}GET("/users/{id}")
 * RipResponse{@literal <}User{@literal >} getUser({@literal @}PathParam("id") String id);
 * </pre>
 *
 * {@code T} is decoded exactly as it would be for a plain return type -
 * {@code String} for the raw body, {@code Void} to discard it, anything else
 * deserialized from JSON. A non-2xx response still throws
 * {@link com.shri.restinpeace.exception.RestInPeaceHttpException} instead of
 * producing a {@code RipResponse} - this type only ever describes a
 * successful response.
 *
 * @param <T> the decoded body type
 */
public final class RipResponse<T> {

	private final int status;
	private final Map<String, List<String>> headers;
	private final T body;

	/**
	 * @param status  the HTTP response status code
	 * @param headers the response headers, keyed by name (case-insensitively)
	 * @param body    the decoded response body
	 */
	public RipResponse(int status, Map<String, List<String>> headers, T body) {
		this.status = status;
		this.headers = headers;
		this.body = body;
	}

	/**
	 * @return the HTTP response status code
	 */
	public int getStatus() {
		return status;
	}

	/**
	 * @return the response headers, keyed by name (case-insensitively); a
	 *         name absent from the response maps to no entry, not a
	 *         {@code null} value
	 */
	public Map<String, List<String>> getHeaders() {
		return headers;
	}

	/**
	 * Returns the first value of a response header, since most headers only
	 * ever carry one.
	 *
	 * @param name the header name, matched case-insensitively
	 * @return the header's first value, or {@code null} if it wasn't sent
	 */
	public String getHeader(String name) {
		List<String> values = headers.get(name);
		return values == null || values.isEmpty() ? null : values.get(0);
	}

	/**
	 * @return the decoded response body
	 */
	public T getBody() {
		return body;
	}

}
