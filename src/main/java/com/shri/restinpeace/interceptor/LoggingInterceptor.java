package com.shri.restinpeace.interceptor;

import java.util.function.Consumer;

/**
 * Pre-built interceptor that logs a line before a request goes out and
 * another when its response comes back, including the elapsed time. Sends to
 * {@code System.out::println} by default; pass a {@link Consumer} to route
 * lines to a real logger instead, without pulling a logging framework into
 * this library's own dependencies.
 */
public class LoggingInterceptor implements RequestInterceptor {

	private static final String START_TIME_ATTRIBUTE = "com.shri.restinpeace.interceptor.LoggingInterceptor.startTime";

	private final Consumer<String> sink;

	public LoggingInterceptor() {
		this(System.out::println);
	}

	public LoggingInterceptor(Consumer<String> sink) {
		this.sink = sink;
	}

	@Override
	public void beforeRequest(RequestContext context) {
		context.setAttribute(START_TIME_ATTRIBUTE, System.currentTimeMillis());
		sink.accept(String.format("--> %s %s", context.getHttpMethod(), context.getUrl()));
	}

	@Override
	public void afterResponse(RequestContext context, int status, Object body) {
		Object startTime = context.getAttribute(START_TIME_ATTRIBUTE);
		String duration = startTime instanceof Long ? (System.currentTimeMillis() - (Long) startTime) + "ms" : "?";
		sink.accept(String.format("<-- %s %s %d (%s)", context.getHttpMethod(), context.getUrl(), status, duration));
	}

}
