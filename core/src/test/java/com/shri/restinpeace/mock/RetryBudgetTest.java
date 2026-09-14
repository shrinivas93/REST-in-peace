package com.shri.restinpeace.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import kong.unirest.JsonObjectMapper;

import com.shri.restinpeace.RIP;
import com.shri.restinpeace.RipClientConfig;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.exception.RestInPeaceHttpException;

/**
 * {@code RipClientConfig.Builder#retryBudget(int, long)} - a token-bucket
 * cap on the *total* retries one client performs across every call, on top
 * of (never instead of) each call's own {@code @Retry#times()}. Against a
 * real {@link MockRestServer} using {@link MockServerTestApi#createOrder}
 * (POST /orders, {@code @Retry(delayMillis = 1)} - default {@code times=3},
 * {@code retryOnStatus={429,502,503,504}}).
 */
class RetryBudgetTest {

	private MockRestServer server;

	@BeforeEach
	void setUp() {
		RIP.setObjectMapper(new JsonObjectMapper());
		server = MockRestServer.start();
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	@Test
	void budgetOfOne_stopsTheSecondRetryEvenThoughTimesAllowsMore() {
		MockServerTestApi api = RIP.getClient(MockServerTestApi.class,
				RipClientConfig.builder().baseUrl(server.baseUrl()).retryBudget(1, 60_000).build());
		server.on(HTTPMethod.POST, "/orders", MockResponse.status(503, "down"));

		RestInPeaceHttpException exception = assertThrows(RestInPeaceHttpException.class,
				() -> api.createOrder("{\"sku\":\"sku-1\"}"));

		assertEquals(503, exception.getStatus());
		// attempt 1 (fails) + 1 retry (the single budgeted token) = 2, not
		// createOrder's own times=3 worth of attempts.
		assertEquals(2, server.requestCount());
	}

	@Test
	void exhaustedBudget_isSharedAcrossSeparateCallsOnTheSameClient() {
		MockServerTestApi api = RIP.getClient(MockServerTestApi.class,
				RipClientConfig.builder().baseUrl(server.baseUrl()).retryBudget(1, 60_000).build());
		server.on(HTTPMethod.POST, "/orders", MockResponse.status(503, "down"));

		assertThrows(RestInPeaceHttpException.class, () -> api.createOrder("{\"sku\":\"sku-1\"}"));
		assertThrows(RestInPeaceHttpException.class, () -> api.createOrder("{\"sku\":\"sku-2\"}"));

		// First call: 1 attempt + 1 retry (drains the only token) = 2.
		// Second call: budget already empty, no retry at all = 1.
		assertEquals(3, server.requestCount());
	}

	@Test
	void budgetRefillsOverTime_allowsRetryingAgainAfterTheWindowElapses() throws InterruptedException {
		MockServerTestApi api = RIP.getClient(MockServerTestApi.class,
				RipClientConfig.builder().baseUrl(server.baseUrl()).retryBudget(1, 50).build());
		server.on(HTTPMethod.POST, "/orders", MockResponse.status(503, "down"));

		assertThrows(RestInPeaceHttpException.class, () -> api.createOrder("{\"sku\":\"sku-1\"}"));
		assertEquals(2, server.requestCount()); // budget drained by this call's own retry

		Thread.sleep(100); // past the 50ms window - the single token refills

		assertThrows(RestInPeaceHttpException.class, () -> api.createOrder("{\"sku\":\"sku-2\"}"));
		assertEquals(4, server.requestCount()); // this call also gets its 1 retry
	}

	@Test
	void withoutARetryBudgetConfigured_retriesUpToTimesAsBefore() {
		MockServerTestApi api = RIP.getClient(MockServerTestApi.class,
				RipClientConfig.builder().baseUrl(server.baseUrl()).build());
		server.onFlaky(HTTPMethod.POST, "/orders", 2, MockResponse.status(503, ""),
				MockResponse.ok("{\"orderId\":\"new-1\"}"));

		String result = api.createOrder("{\"sku\":\"sku-1\"}");

		assertEquals("{\"orderId\":\"new-1\"}", result);
		assertEquals(3, server.requestCount());
	}

}
