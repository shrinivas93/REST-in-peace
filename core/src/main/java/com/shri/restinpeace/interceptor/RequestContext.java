package com.shri.restinpeace.interceptor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.shri.restinpeace.constant.HTTPMethod;

/**
 * Per-call state passed to a {@link RequestInterceptor}'s
 * {@code beforeRequest}/{@code afterResponse}, sharing one instance across
 * both so an interceptor can correlate state between them (e.g. a start
 * timestamp for measuring request duration, via {@link #setAttribute}).
 */
public final class RequestContext {

	private final HTTPMethod httpMethod;
	private final String url;
	private final Map<String, String> headers = new LinkedHashMap<>();
	private final Map<String, Object> attributes = new HashMap<>();

	/**
	 * Creates the context for a single call.
	 *
	 * @param httpMethod the HTTP method of the request being made
	 * @param url        the fully resolved URL of the request being made
	 */
	public RequestContext(HTTPMethod httpMethod, String url) {
		this.httpMethod = httpMethod;
		this.url = url;
	}

	/**
	 * Returns the HTTP method of the request being made.
	 *
	 * @return the HTTP method
	 */
	public HTTPMethod getHttpMethod() {
		return httpMethod;
	}

	/**
	 * Returns the fully resolved URL of the request being made.
	 *
	 * @return the URL
	 */
	public String getUrl() {
		return url;
	}

	/**
	 * Adds a header to be sent with the request. Only effective when called
	 * from {@code beforeRequest} - the request has already been sent by the
	 * time {@code afterResponse} runs.
	 *
	 * @param name  the header name
	 * @param value the header value
	 */
	public void addHeader(String name, String value) {
		headers.put(name, value);
	}

	/**
	 * Returns the headers added so far via {@link #addHeader(String, String)}.
	 *
	 * @return the headers
	 */
	public Map<String, String> getHeaders() {
		return headers;
	}

	/**
	 * Lets an interceptor stash arbitrary per-call state in
	 * {@code beforeRequest} and read it back in {@code afterResponse} (e.g. a
	 * start timestamp to compute request duration).
	 *
	 * @param key   the attribute key
	 * @param value the attribute value
	 */
	public void setAttribute(String key, Object value) {
		attributes.put(key, value);
	}

	/**
	 * Reads back an attribute previously stashed via {@link #setAttribute}.
	 *
	 * @param key the attribute key
	 * @return the attribute value, or {@code null} if none was set
	 */
	public Object getAttribute(String key) {
		return attributes.get(key);
	}

}
