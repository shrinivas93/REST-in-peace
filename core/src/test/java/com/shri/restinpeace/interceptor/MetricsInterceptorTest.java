package com.shri.restinpeace.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.constant.HTTPMethod;

class MetricsInterceptorTest {

	private static final class RecordedCall {
		final String httpMethod;
		final String url;
		final int status;
		final long durationMillis;

		RecordedCall(String httpMethod, String url, int status, long durationMillis) {
			this.httpMethod = httpMethod;
			this.url = url;
			this.status = status;
			this.durationMillis = durationMillis;
		}
	}

	@Test
	void beforeAndAfter_reportsAKnownNonNegativeDuration() {
		List<RecordedCall> calls = new ArrayList<>();
		MetricsInterceptor interceptor = new MetricsInterceptor(
				(httpMethod, url, status, durationMillis) -> calls.add(new RecordedCall(httpMethod, url, status, durationMillis)));
		RequestContext context = new RequestContext(HTTPMethod.GET, "https://api.example.com/orders/42");

		interceptor.beforeRequest(context);
		interceptor.afterResponse(context, 200, "ok");

		assertEquals(1, calls.size());
		assertEquals("GET", calls.get(0).httpMethod);
		assertEquals("https://api.example.com/orders/42", calls.get(0).url);
		assertEquals(200, calls.get(0).status);
		assertTrue(calls.get(0).durationMillis >= 0);
	}

	@Test
	void afterResponse_withNoPrecedingBeforeRequest_reportsUnknownDurationAsNegativeOne() {
		List<RecordedCall> calls = new ArrayList<>();
		MetricsInterceptor interceptor = new MetricsInterceptor(
				(httpMethod, url, status, durationMillis) -> calls.add(new RecordedCall(httpMethod, url, status, durationMillis)));
		RequestContext context = new RequestContext(HTTPMethod.GET, "https://api.example.com/orders/42");

		interceptor.afterResponse(context, 200, "ok");

		assertEquals(-1L, calls.get(0).durationMillis);
	}

}
