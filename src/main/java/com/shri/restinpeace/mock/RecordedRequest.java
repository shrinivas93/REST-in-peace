package com.shri.restinpeace.mock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.sun.net.httpserver.HttpExchange;

import com.shri.restinpeace.constant.HTTPMethod;

/**
 * A single request {@link MockRestServer} actually received, for a test to
 * assert against after making a call through a {@code @RestClient}
 * interface.
 */
public final class RecordedRequest {

	private final HTTPMethod httpMethod;
	private final String path;
	private final Map<String, List<String>> queryParams;
	private final Map<String, List<String>> headers;
	private final String body;

	private RecordedRequest(HTTPMethod httpMethod, String path, Map<String, List<String>> queryParams,
			Map<String, List<String>> headers, String body) {
		this.httpMethod = httpMethod;
		this.path = path;
		this.queryParams = queryParams;
		this.headers = headers;
		this.body = body;
	}

	static RecordedRequest capture(HttpExchange exchange) throws IOException {
		HTTPMethod httpMethod = HTTPMethod.valueOf(exchange.getRequestMethod().toUpperCase(java.util.Locale.ROOT));
		String path = exchange.getRequestURI().getPath();
		Map<String, List<String>> queryParams = parseQuery(exchange.getRequestURI().getRawQuery());
		Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		headers.putAll(exchange.getRequestHeaders());
		String body = readBody(exchange.getRequestBody());
		return new RecordedRequest(httpMethod, path, queryParams, headers, body);
	}

	/**
	 * Returns the HTTP method of the request.
	 *
	 * @return the HTTP method
	 */
	public HTTPMethod getHttpMethod() {
		return httpMethod;
	}

	/**
	 * Returns the request path, without the query string.
	 *
	 * @return the path
	 */
	public String getPath() {
		return path;
	}

	/**
	 * Returns every value of a query param, matched case-sensitively (query
	 * param names, unlike headers, are case-sensitive per the URL spec).
	 *
	 * @param name the query param name
	 * @return the query param's values, or an empty list if it wasn't sent
	 */
	public List<String> getQueryParams(String name) {
		return queryParams.getOrDefault(name, java.util.Collections.emptyList());
	}

	/**
	 * Returns the first value of a query param, since most are only ever sent
	 * once.
	 *
	 * @param name the query param name
	 * @return the query param's first value, or {@code null} if it wasn't sent
	 */
	public String getQueryParam(String name) {
		List<String> values = getQueryParams(name);
		return values.isEmpty() ? null : values.get(0);
	}

	/**
	 * Returns the request headers, keyed by name (case-insensitively).
	 *
	 * @return the headers
	 */
	public Map<String, List<String>> getHeaders() {
		return headers;
	}

	/**
	 * Returns the first value of a request header, matched case-insensitively.
	 *
	 * @param name the header name
	 * @return the header's first value, or {@code null} if it wasn't sent
	 */
	public String getHeader(String name) {
		List<String> values = headers.get(name);
		return values == null || values.isEmpty() ? null : values.get(0);
	}

	/**
	 * Returns the raw request body.
	 *
	 * @return the request body, or an empty string if none was sent
	 */
	public String getBody() {
		return body;
	}

	private static Map<String, List<String>> parseQuery(String rawQuery) {
		Map<String, List<String>> params = new LinkedHashMap<>();
		if (rawQuery == null || rawQuery.isEmpty()) {
			return params;
		}
		for (String pair : rawQuery.split("&")) {
			int equals = pair.indexOf('=');
			String name = equals < 0 ? pair : pair.substring(0, equals);
			String value = equals < 0 ? "" : pair.substring(equals + 1);
			params.computeIfAbsent(decode(name), key -> new ArrayList<>()).add(decode(value));
		}
		return params;
	}

	private static String decode(String value) {
		try {
			return java.net.URLDecoder.decode(value, "UTF-8");
		} catch (java.io.UnsupportedEncodingException e) {
			throw new AssertionError("UTF-8 is always supported.", e);
		}
	}

	private static String readBody(InputStream inputStream) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		byte[] chunk = new byte[1024];
		int read;
		while ((read = inputStream.read(chunk)) != -1) {
			buffer.write(chunk, 0, read);
		}
		return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
	}

}
