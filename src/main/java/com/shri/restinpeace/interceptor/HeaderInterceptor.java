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

	public HeaderInterceptor(String name, String value) {
		this(name, () -> value);
	}

	public HeaderInterceptor(String name, Supplier<String> valueSupplier) {
		this.valueSuppliers = new LinkedHashMap<>();
		this.valueSuppliers.put(name, valueSupplier);
	}

	public HeaderInterceptor(Map<String, Supplier<String>> valueSuppliers) {
		this.valueSuppliers = new LinkedHashMap<>(valueSuppliers);
	}

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
