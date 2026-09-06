package com.shri.restinpeace.interceptor;

/**
 * Pre-built interceptor that times every request and reports it, once its
 * response comes back, to a {@link MetricsSink} - the metrics counterpart of
 * {@link LoggingInterceptor}, following the exact same "bring your own sink"
 * shape so RIP doesn't have to depend on Micrometer or any other metrics
 * library to support one.
 *
 * <p>
 * Register this first, before any other interceptor, if it should measure the
 * total time a call takes including every other interceptor's own work;
 * register it last if it should measure only the network call itself - the
 * same registration-order tradeoff {@link RequestInterceptor}'s own javadoc
 * describes.
 *
 * <p>
 * Only a call that actually receives a response is reported - a transport
 * failure (no response at all) never reaches {@code afterResponse}, so it
 * produces no sample through this interceptor. A {@code @Retry}'d call
 * reports one sample per attempt, since every attempt gets its own
 * {@code afterResponse} notification.
 */
public class MetricsInterceptor implements RequestInterceptor {

	private static final String START_TIME_ATTRIBUTE = "com.shri.restinpeace.interceptor.MetricsInterceptor.startTime";

	private final MetricsSink sink;

	/**
	 * Reports each call's metrics to the given sink.
	 *
	 * @param sink receives one call's metrics per completed response
	 */
	public MetricsInterceptor(MetricsSink sink) {
		this.sink = sink;
	}

	@Override
	public void beforeRequest(RequestContext context) {
		context.setAttribute(START_TIME_ATTRIBUTE, System.currentTimeMillis());
	}

	@Override
	public void afterResponse(RequestContext context, int status, Object body) {
		Object startTime = context.getAttribute(START_TIME_ATTRIBUTE);
		long durationMillis = startTime instanceof Long ? System.currentTimeMillis() - (Long) startTime : -1;
		sink.recordCall(context.getHttpMethod().name(), context.getUrl(), status, durationMillis);
	}

}
