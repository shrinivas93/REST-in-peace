package com.shri.restinpeace.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.shri.restinpeace.RipClientConfig;
import com.shri.restinpeace.interceptor.RequestContext;
import com.shri.restinpeace.interceptor.RequestInterceptor;

import kong.unirest.HttpRequest;
import kong.unirest.HttpResponse;

/**
 * Runs the global and per-client {@link RequestInterceptor}s registered
 * against a request/response. Extracted out of {@link RequestExecutor} since
 * interceptor dispatch is a genuinely separate concern; holds
 * {@code configuredInterceptors} and a {@link ResponseDecoder} (to decode a
 * response's body for {@code afterResponse}) since those are the per-client
 * state this cluster needs. {@link RetryExecutor} holds a direct reference
 * to its owning {@link RequestExecutor}'s dispatcher instead of the
 * {@code RequestExecutor} itself, since retry needs to report every attempt,
 * not just the final one, to interceptors.
 */
final class InterceptorDispatcher {

	private static final List<RequestInterceptor> INTERCEPTORS = new CopyOnWriteArrayList<>();

	private final List<RequestInterceptor> configuredInterceptors;
	private final ResponseDecoder responseDecoder;

	InterceptorDispatcher(List<RequestInterceptor> configuredInterceptors, ResponseDecoder responseDecoder) {
		this.configuredInterceptors = configuredInterceptors;
		this.responseDecoder = responseDecoder;
	}

	/**
	 * Registers a global interceptor applied to every request/response made
	 * through RIP. See
	 * {@link com.shri.restinpeace.RIP#addInterceptor(RequestInterceptor)}.
	 *
	 * @param interceptor the interceptor to register
	 */
	static void addInterceptor(RequestInterceptor interceptor) {
		INTERCEPTORS.add(interceptor);
	}

	/**
	 * Removes one previously registered global interceptor by identity, for
	 * an embedder that needs to reverse exactly what it added rather than
	 * every globally registered interceptor (see
	 * {@link com.shri.restinpeace.RIP#removeInterceptor(RequestInterceptor)}) -
	 * e.g. a Spring context that registered its own beans on startup and
	 * must undo only those on shutdown, since another context's interceptors
	 * sharing this same static registry may still be live.
	 *
	 * @param interceptor the interceptor instance to remove; a no-op if it
	 *                     was never registered (or already removed)
	 */
	static void removeInterceptor(RequestInterceptor interceptor) {
		INTERCEPTORS.remove(interceptor);
	}

	/** Removes all registered interceptors. */
	static void clearInterceptors() {
		INTERCEPTORS.clear();
	}

	/**
	 * Every interceptor that applies to this instance's calls - every
	 * globally registered one, followed by this client's own (from
	 * {@link RipClientConfig.Builder#interceptors}, if any) - global ones
	 * bracket everything, including this client's own, the same "onion"
	 * ordering {@link RequestInterceptor}'s own javadoc describes.
	 *
	 * @return the combined, ordered interceptor list; the same {@link #INTERCEPTORS}
	 *         instance when this client has no interceptors of its own, to
	 *         avoid an allocation in the common case
	 */
	private List<RequestInterceptor> effectiveInterceptors() {
		if (configuredInterceptors.isEmpty()) {
			return INTERCEPTORS;
		}
		List<RequestInterceptor> combined = new ArrayList<>(INTERCEPTORS);
		combined.addAll(configuredInterceptors);
		return combined;
	}

	HttpRequest<?> applyInterceptors(HttpRequest<?> request, RequestContext context) {
		List<RequestInterceptor> interceptors = effectiveInterceptors();
		if (interceptors.isEmpty()) {
			return request;
		}
		interceptors.forEach(interceptor -> interceptor.beforeRequest(context));
		context.getHeaders().forEach(request::header);
		return request;
	}

	/**
	 * Notifies every applicable interceptor's {@code afterResponse}, in
	 * "onion" (LIFO) order. Called directly by {@link RetryExecutor} - retry
	 * needs to report every attempt, not just the final one.
	 */
	<B> void notifyAfterResponse(RequestContext context, HttpResponse<B> response, Class<?> errorType,
			Class<?> returnType) {
		List<RequestInterceptor> interceptors = effectiveInterceptors();
		if (interceptors.isEmpty()) {
			return;
		}
		Object body = responseDecoder.decodeBody(response, errorType, returnType);
		// LIFO, mirroring beforeRequest: the first interceptor registered wraps every
		// other one and is notified last, symmetric with it running beforeRequest first.
		List<RequestInterceptor> reversed = new ArrayList<>(interceptors);
		Collections.reverse(reversed);
		reversed.forEach(interceptor -> interceptor.afterResponse(context, response.getStatus(), body));
	}

}
