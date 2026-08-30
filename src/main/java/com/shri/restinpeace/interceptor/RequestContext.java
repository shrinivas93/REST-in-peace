package com.shri.restinpeace.interceptor;

import java.util.LinkedHashMap;
import java.util.Map;

import com.shri.restinpeace.constant.HTTPMethod;

public final class RequestContext {

	private final HTTPMethod httpMethod;
	private final String url;
	private final Map<String, String> headers = new LinkedHashMap<>();

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

}
