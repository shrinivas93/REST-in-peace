package com.shri.restinpeace.interceptor;

/**
 * Global hook into every request/response made through RIP, registered via
 * {@link com.shri.restinpeace.RIP#addInterceptor(RequestInterceptor)}.
 *
 * <p>
 * Both methods are observers, not a retry pipeline: {@code beforeRequest} can
 * add headers (or abort the call by throwing) before it goes out, and
 * {@code afterResponse} is notified once the response is back, but neither
 * can cause the request to be re-sent. A retry policy needs a different
 * mechanism than a passive interceptor and isn't supported here.
 *
 * <p>
 * When several interceptors are registered, they run "onion"-style: {@code
 * beforeRequest} runs in registration order, but {@code afterResponse} runs
 * in the reverse order, so the first interceptor registered wraps every other
 * one and is the last to see the response - the same pairing used by OkHttp,
 * Servlet filters, and most middleware chains. Register an interceptor first
 * if it needs to bracket everything else's work (e.g. a timer measuring total
 * call overhead); register it last if it needs to sit closest to the actual
 * network call (e.g. a timer measuring only network latency).
 */
public interface RequestInterceptor {

	default void beforeRequest(RequestContext context) {
	}

	default void afterResponse(RequestContext context, int status, Object body) {
	}

}
