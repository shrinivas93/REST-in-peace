package com.shri.restinpeace.interceptor;

/**
 * Receives one completed call's metrics from {@link MetricsInterceptor} -
 * implement this to route them into Micrometer, a homegrown registry, or
 * anything else, without RIP itself depending on a metrics library.
 */
public interface MetricsSink {

	/**
	 * Called once per completed call, right after its response comes back.
	 * Never called for a call that fails at the transport level (a connection
	 * refused, a timeout with no response at all) - only a call that actually
	 * received a response, successful or not, produces a sample. A retried
	 * {@code @Retry} call reports one sample per attempt, not just the final
	 * one.
	 *
	 * @param httpMethod    the HTTP method, e.g. {@code "GET"}
	 * @param url           the fully resolved URL that was called
	 * @param status        the HTTP response status code
	 * @param durationMillis how long the call took, from just before it was
	 *                       sent to just after its response came back
	 */
	void recordCall(String httpMethod, String url, int status, long durationMillis);

}
