package com.shri.restinpeace.interceptor;

import java.util.function.Supplier;

/**
 * Pre-built interceptor that injects a header into every request - the common
 * "attach an auth token to every call" use case. Use the {@link Supplier}
 * constructor when the value can change between calls (e.g. a token that gets
 * refreshed).
 */
public class HeaderInterceptor implements RequestInterceptor {

	private final String name;
	private final Supplier<String> valueSupplier;

	public HeaderInterceptor(String name, String value) {
		this(name, () -> value);
	}

	public HeaderInterceptor(String name, Supplier<String> valueSupplier) {
		this.name = name;
		this.valueSupplier = valueSupplier;
	}

	@Override
	public void beforeRequest(RequestContext context) {
		context.addHeader(name, valueSupplier.get());
	}

}
