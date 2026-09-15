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

	/**
	 * Called before the request is sent. Can add headers via
	 * {@link RequestContext#addHeader}, or abort the call by throwing.
	 *
	 * @param context the request being made
	 */
	default void beforeRequest(RequestContext context) {
	}

	/**
	 * Called after every registered interceptor's {@code beforeRequest} has
	 * run (in the same FIFO registration order as {@code beforeRequest}
	 * itself) - returning a non-{@code null} {@link ShortCircuitResponse}
	 * skips the network call entirely and uses it as this call's response
	 * instead, decoded exactly as a real one would be (including throwing
	 * {@link com.shri.restinpeace.exception.RestInPeaceHttpException} for a
	 * non-2xx status). The first interceptor (in that same FIFO order) to
	 * return non-{@code null} wins; every interceptor's {@code afterResponse}
	 * still runs afterward, exactly as it would for a real response.
	 * Enables a feature-flag bypass, a canary short-circuit, or a
	 * lightweight record/replay mode built on the interceptor chain instead
	 * of a real network dependency.
	 *
	 * <p>
	 * A short-circuited response still goes through this client's own
	 * caching/retry configuration exactly like a real one would (e.g. it may
	 * get cached if it carries cacheable headers, or retried if its status
	 * matches {@code @Retry#retryOnStatus()} - the latter simply re-invokes
	 * this method again instead of a real network call, so it's harmless if
	 * wasteful).
	 *
	 * @param context the request that would otherwise be sent
	 * @return a synthetic response to short-circuit with, or {@code null} to
	 *         let the request proceed normally
	 */
	default ShortCircuitResponse shortCircuit(RequestContext context) {
		return null;
	}

	/**
	 * Called once the response is back.
	 *
	 * @param context the request that was made
	 * @param status  the HTTP response status code
	 * @param body    the response body - a {@code String}, a deserialized
	 *                object, or {@code null} for a {@code void}-returning method
	 */
	default void afterResponse(RequestContext context, int status, Object body) {
	}

}
