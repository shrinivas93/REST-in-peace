package com.shri.restinpeace.interceptor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.shri.restinpeace.constant.HTTPMethod;

public final class RequestContext {

	private final HTTPMethod httpMethod;
	private final String url;
	private final Map<String, String> headers = new LinkedHashMap<>();
	private final Map<String, Object> attributes = new HashMap<>();

	public RequestContext(HTTPMethod httpMethod, String url) {
		this.httpMethod = httpMethod;
		this.url = url;
	}

	public HTTPMethod getHttpMethod() {
		return httpMethod;
	}

	public String getUrl() {
		return url;
	}

	public void addHeader(String name, String value) {
		headers.put(name, value);
	}

	public Map<String, String> getHeaders() {
		return headers;
	}

	/**
	 * Lets an interceptor stash arbitrary per-call state in
	 * {@code beforeRequest} and read it back in {@code afterResponse} (e.g. a
	 * start timestamp to compute request duration).
	 */
	public void setAttribute(String key, Object value) {
		attributes.put(key, value);
	}

	public Object getAttribute(String key) {
		return attributes.get(key);
	}

}
