package com.shri.restinpeace.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import kong.unirest.JsonObjectMapper;

import com.shri.restinpeace.CircuitBreakerConfig;
import com.shri.restinpeace.CircuitBreakerProvider;
import com.shri.restinpeace.RIP;
import com.shri.restinpeace.RipClientConfig;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.exception.CircuitOpenException;
import com.shri.restinpeace.exception.RestInPeaceHttpException;

/**
 * {@code RipClientConfig.Builder#circuitBreaker(CircuitBreakerConfig)} against
 * a real {@link MockRestServer} using {@link MockServerTestApi#getOrder} (GET
 * /orders/{id}, no {@code @Retry} - the compile-time-generated dispatch path,
 * per that interface's own javadoc). Proves the breaker genuinely skips the
 * network call once open (not just that it throws), and that it recovers.
 */
class CircuitBreakerIntegrationTest {

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
	void repeatedFailures_tripTheBreakerAndSkipTheNetworkCallEntirely() {
		MockServerTestApi api = RIP.getClient(MockServerTestApi.class,
				RipClientConfig.builder().baseUrl(server.baseUrl())
						.circuitBreaker(CircuitBreakerConfig.builder().slidingWindowSize(4).minimumNumberOfCalls(4)
								.failureRateThreshold(50).waitDurationInOpenState(Duration.ofSeconds(30)).build())
						.build());
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.status(500, "down"));

		// 4 real failures - trips the breaker (100% >= 50%).
		for (int i = 0; i < 4; i++) {
			assertThrows(RestInPeaceHttpException.class, () -> api.getOrder("1", "false"));
		}
		assertEquals(4, server.requestCount());

		// Open now: refused without ever reaching the server - request count stays put.
		assertThrows(CircuitOpenException.class, () -> api.getOrder("1", "false"));
		assertEquals(4, server.requestCount());
	}

	@Test
	void afterCooldown_aSuccessfulTrialCallClosesTheBreakerAgain() throws InterruptedException {
		MockServerTestApi api = RIP.getClient(MockServerTestApi.class,
				RipClientConfig.builder().baseUrl(server.baseUrl())
						.circuitBreaker(CircuitBreakerConfig.builder().slidingWindowSize(2).minimumNumberOfCalls(2)
								.failureRateThreshold(50).waitDurationInOpenState(Duration.ofMillis(50))
								.permittedCallsInHalfOpenState(1).build())
						.build());
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.status(500, "down"));

		assertThrows(RestInPeaceHttpException.class, () -> api.getOrder("1", "false"));
		assertThrows(RestInPeaceHttpException.class, () -> api.getOrder("1", "false"));
		assertEquals(2, server.requestCount());

		// Open: refused, server never contacted a 3rd time.
		assertThrows(CircuitOpenException.class, () -> api.getOrder("1", "false"));
		assertEquals(2, server.requestCount());

		Thread.sleep(100);
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{\"id\":\"1\"}"));

		// Cooldown elapsed: the half-open trial call is let through and reaches the
		// server again - succeeding closes the breaker.
		String result = api.getOrder("1", "false");
		assertEquals("{\"id\":\"1\"}", result);
		assertEquals(3, server.requestCount());

		// Closed again: further calls flow through normally.
		assertEquals("{\"id\":\"1\"}", api.getOrder("1", "false"));
		assertEquals(4, server.requestCount());
	}

	@Test
	void circuitOpenException_isNeverRetried() {
		MockServerTestApi api = RIP.getClient(MockServerTestApi.class,
				RipClientConfig.builder().baseUrl(server.baseUrl())
						.circuitBreaker(CircuitBreakerConfig.builder().slidingWindowSize(1).minimumNumberOfCalls(1)
								.failureRateThreshold(50).waitDurationInOpenState(Duration.ofSeconds(30)).build())
						.build());
		server.on(HTTPMethod.POST, "/orders", MockResponse.status(503, "down"));

		// createOrder has @Retry(delayMillis = 1); 503 is in its default
		// retryOnStatus, so attempt 1 (a real, network-reaching failure that also
		// trips the breaker, minimumNumberOfCalls=1) would normally be retried -
		// but attempt 2 must be refused as CircuitOpenException instead of
		// consuming one of @Retry's own retryable attempts, and must never reach
		// the server at all.
		assertThrows(CircuitOpenException.class, () -> api.createOrder("{\"sku\":\"sku-1\"}"));
		assertEquals(1, server.requestCount());
	}

	@Test
	void async_repeatedFailures_tripTheBreakerAndSkipTheNetworkCallEntirely() throws Exception {
		MockServerTestApi api = RIP.getClient(MockServerTestApi.class,
				RipClientConfig.builder().baseUrl(server.baseUrl())
						.circuitBreaker(CircuitBreakerConfig.builder().slidingWindowSize(4).minimumNumberOfCalls(4)
								.failureRateThreshold(50).waitDurationInOpenState(Duration.ofSeconds(30)).build())
						.build());
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.status(500, "down"));

		// 4 real failures over the CompletableFuture dispatch path - trips the
		// breaker (100% >= 50%), exactly mirroring the sync test above.
		for (int i = 0; i < 4; i++) {
			CompletableFuture<String> future = api.getOrderAsync("1", "false");
			ExecutionException thrown = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
			assertTrue(thrown.getCause() instanceof RestInPeaceHttpException);
		}
		assertEquals(4, server.requestCount());

		// Open now: refused without ever reaching the server - request count stays
		// put, and the future fails immediately rather than hanging.
		CompletableFuture<String> refused = api.getOrderAsync("1", "false");
		ExecutionException thrown = assertThrows(ExecutionException.class, () -> refused.get(5, TimeUnit.SECONDS));
		assertTrue(thrown.getCause() instanceof CircuitOpenException);
		assertEquals(4, server.requestCount());
	}

	@Test
	void async_circuitOpenException_isNeverRetried() throws Exception {
		MockServerTestApi api = RIP.getClient(MockServerTestApi.class,
				RipClientConfig.builder().baseUrl(server.baseUrl())
						.circuitBreaker(CircuitBreakerConfig.builder().slidingWindowSize(1).minimumNumberOfCalls(1)
								.failureRateThreshold(50).waitDurationInOpenState(Duration.ofSeconds(30)).build())
						.build());
		server.on(HTTPMethod.POST, "/orders", MockResponse.status(503, "down"));

		// createOrderAsync has @Retry(delayMillis = 1) scheduled on a background
		// thread for the async path; 503 is in its default retryOnStatus, so
		// attempt 1 (a real, network-reaching failure that also trips the breaker,
		// minimumNumberOfCalls=1) would normally be retried - but the scheduled
		// attempt 2 must be refused as CircuitOpenException instead of consuming
		// one of @Retry's own retryable attempts, and must never reach the server.
		CompletableFuture<String> future = api.createOrderAsync("{\"sku\":\"sku-1\"}");
		ExecutionException thrown = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
		assertTrue(thrown.getCause() instanceof CircuitOpenException);
		assertEquals(1, server.requestCount());
	}

	private static final class RecordingProvider implements CircuitBreakerProvider {
		boolean permit = true;
		int successCalls;
		Integer lastStatusCode;

		public boolean tryAcquirePermission() {
			return permit;
		}

		public void onSuccess(long durationNanos, int statusCode) {
			successCalls++;
			lastStatusCode = statusCode;
		}

		public void onError(long durationNanos, Throwable t) {
		}
	}

	@Test
	void provider_permissionDenied_refusesWithoutReachingTheServer() {
		RecordingProvider provider = new RecordingProvider();
		provider.permit = false;
		MockServerTestApi api = RIP.getClient(MockServerTestApi.class, RipClientConfig.builder()
				.baseUrl(server.baseUrl()).circuitBreaker((CircuitBreakerProvider) provider).build());
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{\"id\":\"1\"}"));

		assertThrows(CircuitOpenException.class, () -> api.getOrder("1", "false"));
		assertEquals(0, server.requestCount());
	}

	@Test
	void provider_permissionGranted_reachesTheServerAndReportsTheOutcome() {
		RecordingProvider provider = new RecordingProvider();
		MockServerTestApi api = RIP.getClient(MockServerTestApi.class, RipClientConfig.builder()
				.baseUrl(server.baseUrl()).circuitBreaker((CircuitBreakerProvider) provider).build());
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{\"id\":\"1\"}"));

		assertEquals("{\"id\":\"1\"}", api.getOrder("1", "false"));
		assertEquals(1, server.requestCount());
		assertEquals(1, provider.successCalls);
		assertEquals(200, provider.lastStatusCode);
	}

}
