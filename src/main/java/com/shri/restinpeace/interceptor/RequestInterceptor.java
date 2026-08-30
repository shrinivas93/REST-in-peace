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
 */
public interface RequestInterceptor {

	default void beforeRequest(RequestContext context) {
	}

	default void afterResponse(RequestContext context, int status, Object body) {
	}

}
