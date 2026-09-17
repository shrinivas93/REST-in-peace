package com.shri.restinpeace.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.constant.HTTPMethod;

class LoggingInterceptorTest {

	@Test
	void beforeAndAfter_sinkConstructor_logsBothLinesWithKnownDuration() {
		List<String> lines = new ArrayList<>();
		LoggingInterceptor interceptor = new LoggingInterceptor(lines::add);
		RequestContext context = new RequestContext(HTTPMethod.GET, "https://api.example.com/orders/42");

		interceptor.beforeRequest(context);
		interceptor.afterResponse(context, 200, "ok");

		assertEquals("--> GET https://api.example.com/orders/42", lines.get(0));
		assertTrue(lines.get(1).matches("<-- GET https://api.example.com/orders/42 200 \\(\\d+ms\\)"));
	}

	@Test
	void afterResponse_withNoPrecedingBeforeRequest_logsUnknownDuration() {
		List<String> lines = new ArrayList<>();
		LoggingInterceptor interceptor = new LoggingInterceptor(lines::add);
		RequestContext context = new RequestContext(HTTPMethod.GET, "https://api.example.com/orders/42");

		interceptor.afterResponse(context, 200, "ok");

		assertEquals("<-- GET https://api.example.com/orders/42 200 (?)", lines.get(0));
	}

	@Test
	void defaultConstructor_logsToSystemOutWithoutThrowing() {
		LoggingInterceptor interceptor = new LoggingInterceptor();
		RequestContext context = new RequestContext(HTTPMethod.GET, "https://api.example.com/ping");

		interceptor.beforeRequest(context);
		interceptor.afterResponse(context, 200, "ok");
	}

}
