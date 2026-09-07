package com.shri.restinpeace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.exception.RestInPeaceHttpException;
import com.shri.restinpeace.interceptor.CorrelationIdInterceptor;
import com.shri.restinpeace.interceptor.HeaderInterceptor;
import com.shri.restinpeace.interceptor.LoggingInterceptor;
import com.shri.restinpeace.interceptor.MetricsInterceptor;
import com.shri.restinpeace.interceptor.RequestContext;
import com.shri.restinpeace.interceptor.RequestInterceptor;

/**
 * {@link RequestInterceptor} dispatch - ordering (global FIFO before/LIFO
 * after, bracketing a client's own), abort-on-throw, and every built-in
 * interceptor ({@link HeaderInterceptor}, {@link LoggingInterceptor},
 * {@link CorrelationIdInterceptor}, {@link MetricsInterceptor}) - split out
 * of {@code RipIntegrationTest} (see {@link AbstractRipIntegrationTest}).
 */
class RipInterceptorIntegrationTest extends AbstractRipIntegrationTest {

	private static RequestInterceptor namedInterceptor(String name, List<String> order) {
		return new RequestInterceptor() {
			@Override
			public void beforeRequest(RequestContext context) {
				order.add(name + "-before");
			}

			@Override
			public void afterResponse(RequestContext context, int status, Object body) {
				order.add(name + "-after");
			}
		};
	}

	@Test
	void interceptor_beforeRequest_addsHeaderThatGetsSent() {
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public void beforeRequest(RequestContext context) {
				context.addHeader("X-From-Interceptor", "injected");
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		api.get(port, "abc", 7, "custom-value");

		assertEquals("injected", LAST_REQUEST.get().header("X-From-Interceptor"));
	}

	@Test
	void interceptor_afterResponse_calledWithStatusAndStringBody() {
		AtomicReference<Integer> capturedStatus = new AtomicReference<>();
		AtomicReference<Object> capturedBody = new AtomicReference<>();
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public void afterResponse(RequestContext context, int status, Object body) {
				capturedStatus.set(status);
				capturedBody.set(body);
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		api.get(port, "abc", 7, "custom-value");

		assertEquals(200, capturedStatus.get());
		assertEquals("ok", capturedBody.get());
	}

	@Test
	void interceptor_afterResponse_calledWithDeserializedPojo() {
		AtomicReference<Object> capturedBody = new AtomicReference<>();
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public void afterResponse(RequestContext context, int status, Object body) {
				capturedBody.set(body);
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		api.getPayload(port, "abc");

		assertEquals(Payload.class, capturedBody.get().getClass());
		assertEquals("Shrinivas", ((Payload) capturedBody.get()).name);
	}

	@Test
	void interceptor_afterResponse_calledForAsyncCalls()
			throws InterruptedException, ExecutionException, TimeoutException {
		AtomicReference<Integer> capturedStatus = new AtomicReference<>();
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public void afterResponse(RequestContext context, int status, Object body) {
				capturedStatus.set(status);
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		api.getAsync(port, "async").get(5, TimeUnit.SECONDS);

		assertEquals(200, capturedStatus.get());
	}

	@Test
	void interceptor_beforeRequestThrows_abortsRequestBeforeSending() {
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public void beforeRequest(RequestContext context) {
				throw new IllegalStateException("blocked by interceptor");
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		assertThrows(IllegalStateException.class, () -> api.get(port, "abc", 7, "custom-value"));
		assertNull(LAST_REQUEST.get());
	}

	@Test
	void interceptors_beforeRequestRunsFifo_afterResponseRunsLifo() {
		List<String> order = new ArrayList<>();
		RIP.addInterceptor(namedInterceptor("first", order));
		RIP.addInterceptor(namedInterceptor("second", order));
		RIP.addInterceptor(namedInterceptor("third", order));
		LocalApi api = RIP.getClient(LocalApi.class);

		api.get(port, "abc", 7, "custom-value");

		assertEquals(
				Arrays.asList("first-before", "second-before", "third-before", "third-after", "second-after",
						"first-after"),
				order);
	}

	@Test
	void getClient_withRipClientConfigInterceptor_runsForThatClientOnly() {
		List<String> order = new ArrayList<>();
		LocalApi customApi = RIP.getClient(LocalApi.class,
				RipClientConfig.builder().interceptors(Collections.singletonList(namedInterceptor("custom", order)))
						.build());
		LocalApi defaultApi = RIP.getClient(LocalApi.class);

		defaultApi.get(port, "abc", 7, "custom-value");
		assertEquals(Collections.emptyList(), order);

		customApi.get(port, "abc", 7, "custom-value");
		assertEquals(Arrays.asList("custom-before", "custom-after"), order);
	}

	@Test
	void getClient_withGlobalAndRipClientConfigInterceptors_globalBracketsClientSpecific() {
		List<String> order = new ArrayList<>();
		RIP.addInterceptor(namedInterceptor("global", order));
		LocalApi customApi = RIP.getClient(LocalApi.class,
				RipClientConfig.builder().interceptors(Collections.singletonList(namedInterceptor("custom", order)))
						.build());

		customApi.get(port, "abc", 7, "custom-value");

		assertEquals(Arrays.asList("global-before", "custom-before", "custom-after", "global-after"), order);
	}

	@Test
	void headerInterceptor_withStaticValue_addsHeaderToEveryRequest() {
		RIP.addInterceptor(new HeaderInterceptor("Authorization", "Bearer static-token"));
		LocalApi api = RIP.getClient(LocalApi.class);

		api.get(port, "abc", 7, "custom-value");

		assertEquals("Bearer static-token", LAST_REQUEST.get().header("Authorization"));
	}

	@Test
	void headerInterceptor_withSupplier_reevaluatesValuePerCall() {
		AtomicReference<String> token = new AtomicReference<>("token-1");
		RIP.addInterceptor(new HeaderInterceptor("Authorization", token::get));
		LocalApi api = RIP.getClient(LocalApi.class);

		api.get(port, "abc", 7, "custom-value");
		assertEquals("token-1", LAST_REQUEST.get().header("Authorization"));

		token.set("token-2");
		api.get(port, "abc", 7, "custom-value");
		assertEquals("token-2", LAST_REQUEST.get().header("Authorization"));
	}

	@Test
	void headerInterceptor_ofStaticMap_addsAllHeadersToEveryRequest() {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("X-Api-Key", "key-1");
		headers.put("X-Client-Version", "1.2.3");
		headers.put("X-Tenant-Id", "tenant-42");
		RIP.addInterceptor(HeaderInterceptor.of(headers));
		LocalApi api = RIP.getClient(LocalApi.class);

		api.get(port, "abc", 7, "custom-value");

		CapturedRequest request = LAST_REQUEST.get();
		assertEquals("key-1", request.header("X-Api-Key"));
		assertEquals("1.2.3", request.header("X-Client-Version"));
		assertEquals("tenant-42", request.header("X-Tenant-Id"));
	}

	@Test
	void headerInterceptor_withSupplierMap_reevaluatesEachValuePerCall() {
		AtomicReference<String> token = new AtomicReference<>("token-1");
		Map<String, Supplier<String>> suppliers = new LinkedHashMap<>();
		suppliers.put("Authorization", token::get);
		suppliers.put("X-Static", () -> "fixed");
		RIP.addInterceptor(new HeaderInterceptor(suppliers));
		LocalApi api = RIP.getClient(LocalApi.class);

		api.get(port, "abc", 7, "custom-value");
		assertEquals("token-1", LAST_REQUEST.get().header("Authorization"));
		assertEquals("fixed", LAST_REQUEST.get().header("X-Static"));

		token.set("token-2");
		api.get(port, "abc", 7, "custom-value");
		assertEquals("token-2", LAST_REQUEST.get().header("Authorization"));
	}

	@Test
	void loggingInterceptor_logsRequestAndResponseLinesWithDuration() {
		List<String> lines = new ArrayList<>();
		RIP.addInterceptor(new LoggingInterceptor(lines::add));
		LocalApi api = RIP.getClient(LocalApi.class);

		api.get(port, "abc", 7, "custom-value");

		assertEquals(2, lines.size());
		assertTrue(lines.get(0).startsWith("--> GET "));
		assertTrue(lines.get(1).startsWith("<-- GET "));
		assertTrue(lines.get(1).contains(" 200 "));
		assertTrue(lines.get(1).endsWith("ms)"));
	}

	@Test
	void correlationIdInterceptor_default_addsUniqueIdPerRequestUnderDefaultHeader() {
		RIP.addInterceptor(new CorrelationIdInterceptor());
		LocalApi api = RIP.getClient(LocalApi.class);

		api.get(port, "abc", 7, "custom-value");
		String firstId = LAST_REQUEST.get().header(CorrelationIdInterceptor.DEFAULT_HEADER_NAME);

		api.get(port, "abc", 7, "custom-value");
		String secondId = LAST_REQUEST.get().header(CorrelationIdInterceptor.DEFAULT_HEADER_NAME);

		assertTrue(firstId != null && !firstId.isEmpty());
		assertTrue(secondId != null && !secondId.isEmpty());
		assertFalse(firstId.equals(secondId));
	}

	@Test
	void correlationIdInterceptor_customHeaderName_usesConfiguredHeader() {
		RIP.addInterceptor(new CorrelationIdInterceptor("X-Trace-Id"));
		LocalApi api = RIP.getClient(LocalApi.class);

		api.get(port, "abc", 7, "custom-value");

		assertNull(LAST_REQUEST.get().header(CorrelationIdInterceptor.DEFAULT_HEADER_NAME));
		String traceId = LAST_REQUEST.get().header("X-Trace-Id");
		assertTrue(traceId != null && !traceId.isEmpty());
	}

	@Test
	void correlationIdInterceptor_customGenerator_usesProvidedIds() {
		AtomicReference<Integer> counter = new AtomicReference<>(0);
		RIP.addInterceptor(new CorrelationIdInterceptor(CorrelationIdInterceptor.DEFAULT_HEADER_NAME,
				() -> "id-" + counter.updateAndGet(n -> n + 1)));
		LocalApi api = RIP.getClient(LocalApi.class);

		api.get(port, "abc", 7, "custom-value");
		assertEquals("id-1", LAST_REQUEST.get().header(CorrelationIdInterceptor.DEFAULT_HEADER_NAME));

		api.get(port, "abc", 7, "custom-value");
		assertEquals("id-2", LAST_REQUEST.get().header(CorrelationIdInterceptor.DEFAULT_HEADER_NAME));
	}

	@Test
	void correlationIdInterceptor_storesIdOnContext_forOtherInterceptorsToRead() {
		AtomicReference<Object> idSeenByOtherInterceptor = new AtomicReference<>();
		RIP.addInterceptor(new CorrelationIdInterceptor());
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public void afterResponse(RequestContext context, int status, Object body) {
				idSeenByOtherInterceptor.set(context.getAttribute(CorrelationIdInterceptor.ID_ATTRIBUTE));
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		api.get(port, "abc", 7, "custom-value");

		String headerId = LAST_REQUEST.get().header(CorrelationIdInterceptor.DEFAULT_HEADER_NAME);
		assertEquals(headerId, idSeenByOtherInterceptor.get());
	}

	@Test
	void metricsInterceptor_reportsMethodUrlStatusAndDuration() {
		List<String> methods = new ArrayList<>();
		List<Integer> statuses = new ArrayList<>();
		List<Long> durations = new ArrayList<>();
		RIP.addInterceptor(new MetricsInterceptor((httpMethod, url, status, durationMillis) -> {
			methods.add(httpMethod);
			statuses.add(status);
			durations.add(durationMillis);
		}));
		LocalApi api = RIP.getClient(LocalApi.class);

		api.get(port, "abc", 7, "custom-value");

		assertEquals(Collections.singletonList("GET"), methods);
		assertEquals(Collections.singletonList(200), statuses);
		assertEquals(1, durations.size());
		assertTrue(durations.get(0) >= 0);
	}

	@Test
	void metricsInterceptor_retriedCall_reportsOneSampleForEveryAttempt() {
		List<Integer> statuses = new ArrayList<>();
		RIP.addInterceptor(new MetricsInterceptor((httpMethod, url, status, durationMillis) -> statuses.add(status)));
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.getFlaky(port, "x");

		assertEquals("ok", result);
		assertEquals(Arrays.asList(503, 503, 200), statuses);
	}

	@Test
	void interceptor_afterResponse_onErrorStatus_seesDeserializedErrorBody() {
		List<Object> bodies = new ArrayList<>();
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public void afterResponse(RequestContext context, int status, Object body) {
				bodies.add(body);
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		assertThrows(RestInPeaceHttpException.class, () -> api.getWithTypedError(port, "x"));

		assertEquals(1, bodies.size());
		assertTrue(bodies.get(0) instanceof ApiError);
	}

}
