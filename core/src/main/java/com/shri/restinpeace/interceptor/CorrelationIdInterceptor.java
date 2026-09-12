package com.shri.restinpeace.interceptor;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Pre-built interceptor that attaches a fresh correlation/request ID to
 * every request - useful for tracing a call across service boundaries or
 * correlating client-side logs with server-side logs. Defaults to a random
 * UUID per call sent under the {@code X-Request-Id} header; both the header
 * name and the ID generator are configurable.
 *
 * <p>
 * The generated ID is also stashed on the {@link RequestContext} under
 * {@link #ID_ATTRIBUTE}, so another interceptor registered alongside this
 * one (e.g. a logging or metrics interceptor) can read it back in
 * {@code beforeRequest}/{@code afterResponse} to correlate its own output.
 */
public class CorrelationIdInterceptor implements RequestInterceptor {

	/** The header used when no header name is given explicitly. */
	public static final String DEFAULT_HEADER_NAME = "X-Request-Id";

	/** {@link RequestContext} attribute key the generated ID is stashed under. */
	public static final String ID_ATTRIBUTE = "com.shri.restinpeace.interceptor.CorrelationIdInterceptor.id";

	private final String headerName;
	private final Supplier<String> idGenerator;

	/** Generates a random UUID per call, sent under {@link #DEFAULT_HEADER_NAME}. */
	public CorrelationIdInterceptor() {
		this(DEFAULT_HEADER_NAME);
	}

	/**
	 * Generates a random UUID per call, sent under a custom header name.
	 *
	 * @param headerName the header to send the generated ID under
	 */
	public CorrelationIdInterceptor(String headerName) {
		this(headerName, () -> UUID.randomUUID().toString());
	}

	/**
	 * Uses a custom ID generator instead of a random UUID.
	 *
	 * @param headerName  the header to send the generated ID under
	 * @param idGenerator supplies a fresh ID for each request
	 */
	public CorrelationIdInterceptor(String headerName, Supplier<String> idGenerator) {
		this.headerName = headerName;
		this.idGenerator = idGenerator;
	}

	@Override
	public void beforeRequest(RequestContext context) {
		String id = idGenerator.get();
		context.addHeader(headerName, id);
		context.setAttribute(ID_ATTRIBUTE, id);
	}

}
