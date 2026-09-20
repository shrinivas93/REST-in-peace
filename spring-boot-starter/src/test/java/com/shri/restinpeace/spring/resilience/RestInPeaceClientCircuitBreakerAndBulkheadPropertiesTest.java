package com.shri.restinpeace.spring.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import com.shri.restinpeace.annotation.marker.BaseUrl;
import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.exception.BulkheadFullException;
import com.shri.restinpeace.exception.CircuitOpenException;
import com.shri.restinpeace.mock.MockResponse;
import com.shri.restinpeace.mock.MockRestServer;
import com.shri.restinpeace.spring.AutoConfigureMockRestServer;
import com.shri.restinpeace.spring.EnableRestInPeaceClients;

/**
 * Proves {@code rest-in-peace.clients.<name>.circuit-breaker.*}/
 * {@code .bulkhead.*} properties (chunk 6 of
 * {@code docs/design/circuit-breaker-bulkhead.md}) actually reach the
 * registered client's {@code RipClientConfig} - the same way core's own
 * {@code CircuitBreakerIntegrationTest}/{@code BulkheadIntegrationTest} prove
 * it for a hand-built config, just resolved from Spring properties instead
 * of a builder call.
 *
 * <p>
 * Declared in its own dedicated {@code .resilience} sub-package, scanned on
 * its own - see {@code EnableRestInPeaceClientsTest}'s own javadoc for why
 * sharing a scanned package with another test's {@code @RestClient}
 * interface is unsafe.
 */
class RestInPeaceClientCircuitBreakerAndBulkheadPropertiesTest {

	@RestClient
	@BaseUrl("http://localhost:1")
	interface OrderApi {
		@GET("/orders/{id}")
		String getOrder(@PathParam("id") String id);
	}

	@Configuration
	@EnableRestInPeaceClients(basePackages = "com.shri.restinpeace.spring.resilience")
	@AutoConfigureMockRestServer
	static class TestConfig {
	}

	@Test
	void restInPeaceClient_withCircuitBreakerProperties_tripsAfterRepeatedFailuresAndSkipsTheNetworkCall() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			Map<String, Object> properties = new HashMap<>();
			properties.put("rest-in-peace.clients.orderApi.circuit-breaker.sliding-window-size", "4");
			properties.put("rest-in-peace.clients.orderApi.circuit-breaker.minimum-number-of-calls", "4");
			properties.put("rest-in-peace.clients.orderApi.circuit-breaker.failure-rate-threshold", "50");
			properties.put("rest-in-peace.clients.orderApi.circuit-breaker.wait-duration-in-open-state-millis",
					"30000");
			context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
			context.register(TestConfig.class);
			context.refresh();

			MockRestServer server = context.getBean(MockRestServer.class);
			server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.status(500, "down"));
			OrderApi orderApi = context.getBean(OrderApi.class);

			// 4 real failures - trips the breaker (100% >= the 50% threshold).
			for (int i = 0; i < 4; i++) {
				assertThrows(RuntimeException.class, () -> orderApi.getOrder("1"));
			}
			assertEquals(4, server.requestCount());

			// Open now: refused without ever reaching the server - request count
			// stays put, proving the property-bound breaker is the real thing, not
			// just a config object nobody wired up.
			assertThrows(CircuitOpenException.class, () -> orderApi.getOrder("1"));
			assertEquals(4, server.requestCount());
		}
	}

	@Test
	void restInPeaceClient_withTimeBasedSlidingWindowCircuitBreakerProperty_tripsTheBreaker() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			Map<String, Object> properties = new HashMap<>();
			// sliding-window-duration-millis selects the time-based window
			// (CircuitBreakerConfig.Builder#slidingWindowSize(Duration)) instead of
			// the count-based default - set alongside sliding-window-size to prove
			// the duration wins.
			properties.put("rest-in-peace.clients.orderApi.circuit-breaker.sliding-window-size", "999");
			properties.put("rest-in-peace.clients.orderApi.circuit-breaker.sliding-window-duration-millis", "60000");
			properties.put("rest-in-peace.clients.orderApi.circuit-breaker.minimum-number-of-calls", "2");
			properties.put("rest-in-peace.clients.orderApi.circuit-breaker.failure-rate-threshold", "50");
			properties.put("rest-in-peace.clients.orderApi.circuit-breaker.wait-duration-in-open-state-millis",
					"30000");
			context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
			context.register(TestConfig.class);
			context.refresh();

			MockRestServer server = context.getBean(MockRestServer.class);
			server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.status(500, "down"));
			OrderApi orderApi = context.getBean(OrderApi.class);

			assertThrows(RuntimeException.class, () -> orderApi.getOrder("1"));
			assertThrows(RuntimeException.class, () -> orderApi.getOrder("1"));
			assertEquals(2, server.requestCount());

			// If sliding-window-size (999) had won instead of the duration, 2
			// failures wouldn't be enough of a sample to trip anything.
			assertThrows(CircuitOpenException.class, () -> orderApi.getOrder("1"));
			assertEquals(2, server.requestCount());
		}
	}

	@Test
	void restInPeaceClient_withCircuitBreakerHalfOpenStateProperty_recoversAfterCooldown() throws InterruptedException {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			Map<String, Object> properties = new HashMap<>();
			properties.put("rest-in-peace.clients.orderApi.circuit-breaker.sliding-window-size", "2");
			properties.put("rest-in-peace.clients.orderApi.circuit-breaker.minimum-number-of-calls", "2");
			properties.put("rest-in-peace.clients.orderApi.circuit-breaker.failure-rate-threshold", "50");
			properties.put("rest-in-peace.clients.orderApi.circuit-breaker.wait-duration-in-open-state-millis", "50");
			properties.put("rest-in-peace.clients.orderApi.circuit-breaker.permitted-calls-in-half-open-state", "1");
			context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
			context.register(TestConfig.class);
			context.refresh();

			MockRestServer server = context.getBean(MockRestServer.class);
			server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.status(500, "down"));
			OrderApi orderApi = context.getBean(OrderApi.class);

			assertThrows(RuntimeException.class, () -> orderApi.getOrder("1"));
			assertThrows(RuntimeException.class, () -> orderApi.getOrder("1"));
			assertEquals(2, server.requestCount());

			// Open: refused, server never contacted a 3rd time.
			assertThrows(CircuitOpenException.class, () -> orderApi.getOrder("1"));
			assertEquals(2, server.requestCount());

			Thread.sleep(100);
			server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{\"id\":\"1\"}"));

			// Cooldown elapsed: the permitted-calls-in-half-open-state trial call is
			// let through and reaches the server again - succeeding closes the
			// breaker.
			assertEquals("{\"id\":\"1\"}", orderApi.getOrder("1"));
			assertEquals(3, server.requestCount());
		}
	}

	@Test
	void restInPeaceClient_withOnlyFailureRateThresholdCircuitBreakerProperty_stillUsesBuiltInDefaultsForEverythingElse() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			// Every other circuit-breaker.* option left unset -
			// CircuitBreakerConfig.Builder's own defaults apply: a count-based
			// 20-call window and a 10-call minimum sample, so exactly 10 real
			// failures (the default minimumNumberOfCalls, not fewer) are needed
			// before this deliberately tiny 1% failure-rate-threshold can trip
			// anything.
			context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Collections
					.singletonMap("rest-in-peace.clients.orderApi.circuit-breaker.failure-rate-threshold", "1")));
			context.register(TestConfig.class);
			context.refresh();

			MockRestServer server = context.getBean(MockRestServer.class);
			server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.status(500, "down"));
			OrderApi orderApi = context.getBean(OrderApi.class);

			for (int i = 0; i < 10; i++) {
				assertThrows(RuntimeException.class, () -> orderApi.getOrder("1"));
			}
			assertEquals(10, server.requestCount());

			assertThrows(CircuitOpenException.class, () -> orderApi.getOrder("1"));
			assertEquals(10, server.requestCount());
		}
	}

	@Test
	void restInPeaceClient_withOnlyMinimumNumberOfCallsCircuitBreakerProperty_stillUsesBuiltInDefaultFailureRateThreshold() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			// Every other circuit-breaker.* option left unset - the built-in
			// default 50% failure-rate-threshold (and 20-call sliding window)
			// still applies, so 3 real failures (100% of this custom, smaller
			// minimum sample) still trip it.
			context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Collections
					.singletonMap("rest-in-peace.clients.orderApi.circuit-breaker.minimum-number-of-calls", "3")));
			context.register(TestConfig.class);
			context.refresh();

			MockRestServer server = context.getBean(MockRestServer.class);
			server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.status(500, "down"));
			OrderApi orderApi = context.getBean(OrderApi.class);

			for (int i = 0; i < 3; i++) {
				assertThrows(RuntimeException.class, () -> orderApi.getOrder("1"));
			}
			assertEquals(3, server.requestCount());

			assertThrows(CircuitOpenException.class, () -> orderApi.getOrder("1"));
			assertEquals(3, server.requestCount());
		}
	}

	@Test
	void restInPeaceClient_withoutCircuitBreakerProperty_neverTripsEvenAfterRepeatedFailures() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
			MockRestServer server = context.getBean(MockRestServer.class);
			server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.status(500, "down"));
			OrderApi orderApi = context.getBean(OrderApi.class);

			for (int i = 0; i < 10; i++) {
				assertThrows(RuntimeException.class, () -> orderApi.getOrder("1"));
			}
			// No circuit-breaker.* property set at all - every call reaches the
			// server, none refused as CircuitOpenException.
			assertEquals(10, server.requestCount());
		}
	}

	@Test
	void restInPeaceClient_withBulkheadProperties_refusesOnceMaxConcurrentCallsExceeded() throws Exception {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
					Collections.singletonMap("rest-in-peace.clients.orderApi.bulkhead.max-concurrent-calls", "1")));
			context.register(TestConfig.class);
			context.refresh();

			MockRestServer server = context.getBean(MockRestServer.class);
			server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{\"id\":\"1\"}").delay(300));
			OrderApi orderApi = context.getBean(OrderApi.class);

			ExecutorService executor = Executors.newSingleThreadExecutor();
			try {
				Future<String> holding = executor.submit(() -> orderApi.getOrder("1"));
				// Give the held call time to acquire its permit and reach the server
				// before the assertion below - it's still delaying its response.
				Thread.sleep(100);
				assertEquals(1, server.requestCount());

				// The single permit is held by the in-flight call above - refused
				// without ever reaching the server, so the request count stays put.
				assertThrows(BulkheadFullException.class, () -> orderApi.getOrder("2"));
				assertEquals(1, server.requestCount());

				assertEquals("{\"id\":\"1\"}", holding.get(5, TimeUnit.SECONDS));
			} finally {
				executor.shutdownNow();
			}
		}
	}

	@Test
	void restInPeaceClient_withBulkheadMaxWaitDurationProperty_letsACallQueueInsteadOfFailingFast() throws Exception {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			Map<String, Object> properties = new HashMap<>();
			properties.put("rest-in-peace.clients.orderApi.bulkhead.max-concurrent-calls", "1");
			properties.put("rest-in-peace.clients.orderApi.bulkhead.max-wait-duration-millis", "5000");
			context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
			context.register(TestConfig.class);
			context.refresh();

			MockRestServer server = context.getBean(MockRestServer.class);
			server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{\"id\":\"1\"}").delay(200));
			OrderApi orderApi = context.getBean(OrderApi.class);

			ExecutorService executor = Executors.newSingleThreadExecutor();
			try {
				Future<String> holding = executor.submit(() -> orderApi.getOrder("1"));
				Thread.sleep(50);

				// The holding call frees its permit ~200ms in, well within the 5s
				// max-wait-duration-millis - this call must wait and then succeed,
				// proving it actually queued rather than being refused immediately.
				assertEquals("{\"id\":\"1\"}", orderApi.getOrder("2"));
				assertEquals("{\"id\":\"1\"}", holding.get(5, TimeUnit.SECONDS));
				assertEquals(2, server.requestCount());
			} finally {
				executor.shutdownNow();
			}
		}
	}

	@Test
	void restInPeaceClient_withOnlyMaxWaitDurationBulkheadProperty_stillUsesTheBuiltInDefaultConcurrencyCap()
			throws Exception {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			// maxConcurrentCalls left unset - if it hadn't kept BulkheadConfig.Builder's
			// own default (25) instead of, say, falling back to some overly
			// restrictive value, a handful of concurrent calls could be wrongly
			// refused.
			context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Collections
					.singletonMap("rest-in-peace.clients.orderApi.bulkhead.max-wait-duration-millis", "5000")));
			context.register(TestConfig.class);
			context.refresh();

			MockRestServer server = context.getBean(MockRestServer.class);
			server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{\"id\":\"1\"}").delay(100));
			OrderApi orderApi = context.getBean(OrderApi.class);

			ExecutorService executor = Executors.newFixedThreadPool(5);
			try {
				List<Future<String>> futures = new ArrayList<>();
				for (int i = 0; i < 5; i++) {
					futures.add(executor.submit(() -> orderApi.getOrder("1")));
				}
				for (Future<String> future : futures) {
					assertEquals("{\"id\":\"1\"}", future.get(5, TimeUnit.SECONDS));
				}
				assertEquals(5, server.requestCount());
			} finally {
				executor.shutdownNow();
			}
		}
	}

	@Test
	void restInPeaceClient_withoutBulkheadProperty_allowsUnboundedConcurrentCalls() throws Exception {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
			MockRestServer server = context.getBean(MockRestServer.class);
			server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{\"id\":\"1\"}").delay(200));
			OrderApi orderApi = context.getBean(OrderApi.class);

			ExecutorService executor = Executors.newFixedThreadPool(2);
			try {
				Future<String> first = executor.submit(() -> orderApi.getOrder("1"));
				Thread.sleep(50);

				// No bulkhead.* property set at all - a second concurrent call is let
				// through rather than refused as BulkheadFullException.
				assertEquals("{\"id\":\"1\"}", orderApi.getOrder("2"));
				assertEquals("{\"id\":\"1\"}", first.get(5, TimeUnit.SECONDS));
				assertEquals(2, server.requestCount());
			} finally {
				executor.shutdownNow();
			}
		}
	}

}
