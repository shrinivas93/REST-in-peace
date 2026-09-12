package com.shri.restinpeace.interceptor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Pre-built interceptor that injects one or more headers into every request -
 * the common "attach an auth token (or a batch of fixed headers) to every
 * call" use case. Use a {@link Supplier} (single-header constructor, or the
 * {@code Map<String, Supplier<String>>} constructor) when a value can change
 * between calls (e.g. a token that gets refreshed); use {@link #of(Map)} for
 * a fixed set of static headers.
 */
public class HeaderInterceptor implements RequestInterceptor {

	private final Map<String, Supplier<String>> valueSuppliers;

	/**
	 * Injects a single header with a static value.
	 *
	 * @param name  the header name
	 * @param value the static header value
	 */
	public HeaderInterceptor(String name, String value) {
		this(name, () -> value);
	}

	/**
	 * Injects a single header whose value is re-evaluated for each request.
	 *
	 * @param name          the header name
	 * @param valueSupplier supplies the header value for each request
	 */
	public HeaderInterceptor(String name, Supplier<String> valueSupplier) {
		this.valueSuppliers = new LinkedHashMap<>();
		this.valueSuppliers.put(name, valueSupplier);
	}

	/**
	 * Injects several headers, each re-evaluated for every request.
	 *
	 * @param valueSuppliers a header name to value-supplier map, applied to
	 *                       every request
	 */
	public HeaderInterceptor(Map<String, Supplier<String>> valueSuppliers) {
		this.valueSuppliers = new LinkedHashMap<>(valueSuppliers);
	}

	/**
	 * Creates an interceptor for a fixed set of static headers.
	 *
	 * @param headers a header name to value map, applied to every request
	 * @return the interceptor
	 */
	public static HeaderInterceptor of(Map<String, String> headers) {
		Map<String, Supplier<String>> valueSuppliers = new LinkedHashMap<>();
		headers.forEach((name, value) -> valueSuppliers.put(name, () -> value));
		return new HeaderInterceptor(valueSuppliers);
	}

	@Override
	public void beforeRequest(RequestContext context) {
		valueSuppliers.forEach((name, valueSupplier) -> context.addHeader(name, valueSupplier.get()));
	}

}
