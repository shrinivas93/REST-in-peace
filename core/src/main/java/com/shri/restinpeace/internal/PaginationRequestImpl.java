package com.shri.restinpeace.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.shri.restinpeace.PaginationRequest;

/**
 * The sole implementation of {@link PaginationRequest} - constructed only via
 * its static factory methods (which this class's own statics back) and
 * {@link #and}. Public only because {@link PaginationRequest}'s factory
 * methods, in a different package, need to construct it; a
 * {@code PaginationStrategy} never sees this type directly, only the
 * {@link PaginationRequest} interface. {@link PaginationCoordinator} reads
 * its fields directly (same package) to build the next page's fetch.
 */
public final class PaginationRequestImpl implements PaginationRequest {

	private final String url;
	private final Map<String, Object> queryParams;
	private final Map<String, Object> pathParams;
	private final Map<String, Object> headers;
	private final Map<String, Object> bodyFields;

	private PaginationRequestImpl(String url, Map<String, Object> queryParams, Map<String, Object> pathParams,
			Map<String, Object> headers, Map<String, Object> bodyFields) {
		this.url = url;
		this.queryParams = queryParams;
		this.pathParams = pathParams;
		this.headers = headers;
		this.bodyFields = bodyFields;
	}

	public static PaginationRequestImpl toUrl(String url) {
		Objects.requireNonNull(url, "url");
		return new PaginationRequestImpl(url, emptyMap(), emptyMap(), emptyMap(), emptyMap());
	}

	public static PaginationRequestImpl withQueryParam(String name, Object value) {
		return new PaginationRequestImpl(null, singletonMap(name, value), emptyMap(), emptyMap(), emptyMap());
	}

	public static PaginationRequestImpl withPathParam(String name, Object value) {
		return new PaginationRequestImpl(null, emptyMap(), singletonMap(name, value), emptyMap(), emptyMap());
	}

	public static PaginationRequestImpl withHeader(String name, Object value) {
		return new PaginationRequestImpl(null, emptyMap(), emptyMap(), singletonMap(name, value), emptyMap());
	}

	public static PaginationRequestImpl withBodyField(String dottedPath, Object value) {
		return new PaginationRequestImpl(null, emptyMap(), emptyMap(), emptyMap(), singletonMap(dottedPath, value));
	}

	@Override
	public PaginationRequest and(PaginationRequest other) {
		PaginationRequestImpl o = (PaginationRequestImpl) other;
		return new PaginationRequestImpl(url != null ? url : o.url, merge(queryParams, o.queryParams),
				merge(pathParams, o.pathParams), merge(headers, o.headers), merge(bodyFields, o.bodyFields));
	}

	private static Map<String, Object> merge(Map<String, Object> a, Map<String, Object> b) {
		if (a.isEmpty()) {
			return b;
		}
		if (b.isEmpty()) {
			return a;
		}
		Map<String, Object> merged = new LinkedHashMap<>(a);
		merged.putAll(b);
		return merged;
	}

	private static Map<String, Object> emptyMap() {
		return java.util.Collections.emptyMap();
	}

	private static Map<String, Object> singletonMap(String key, Object value) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put(key, value);
		return map;
	}

	public String url() {
		return url;
	}

	public Map<String, Object> queryParams() {
		return queryParams;
	}

	public Map<String, Object> pathParams() {
		return pathParams;
	}

	public Map<String, Object> headers() {
		return headers;
	}

	public Map<String, Object> bodyFields() {
		return bodyFields;
	}

}
