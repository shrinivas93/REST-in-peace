package com.shri.restinpeace.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import kong.unirest.JsonObjectMapper;

import com.shri.restinpeace.BulkheadConfig;
import com.shri.restinpeace.RIP;
import com.shri.restinpeace.RipClientConfig;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.exception.BulkheadFullException;

/**
 * {@code RipClientConfig.Builder#bulkhead(BulkheadConfig)} against a real
 * {@link MockRestServer} using {@link MockServerTestApi#getOrder} (GET
 * /orders/{id} - the compile-time-generated dispatch path, per that
 * interface's own javadoc). Proves the bulkhead genuinely skips the network
 * call once full (not just that it throws), that a released permit lets a
 * subsequent call through, and that {@code maxWaitDuration} lets a call
 * queue for a permit instead of failing immediately.
 */
class BulkheadIntegrationTest {

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
	void aFullBulkhead_refusesWithoutReachingTheServer() throws Exception {
		MockServerTestApi api = RIP.getClient(MockServerTestApi.class, RipClientConfig.builder()
				.baseUrl(server.baseUrl()).bulkhead(BulkheadConfig.builder().maxConcurrentCalls(1).build()).build());
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{\"id\":\"1\"}").delay(300));

		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<String> holding = executor.submit(() -> api.getOrder("1", "false"));
			// Give the held call time to acquire its permit and reach the server
			// before the assertion below - it's still delaying its response.
			Thread.sleep(100);
			assertEquals(1, server.requestCount());

			// The single permit is held by the in-flight call above - refused
			// without ever reaching the server, so the request count stays put.
			assertThrows(BulkheadFullException.class, () -> api.getOrder("2", "false"));
			assertEquals(1, server.requestCount());

			assertEquals("{\"id\":\"1\"}", holding.get(5, TimeUnit.SECONDS));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void permitFreedOnCompletion_letsTheNextCallThrough() {
		MockServerTestApi api = RIP.getClient(MockServerTestApi.class, RipClientConfig.builder()
				.baseUrl(server.baseUrl()).bulkhead(BulkheadConfig.builder().maxConcurrentCalls(1).build()).build());
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{\"id\":\"1\"}"));

		// If the permit from the first call weren't released once it completed,
		// this second, entirely sequential call would find no permit available.
		assertEquals("{\"id\":\"1\"}", api.getOrder("1", "false"));
		assertEquals("{\"id\":\"1\"}", api.getOrder("1", "false"));
		assertEquals(2, server.requestCount());
	}

	@Test
	void maxWaitDuration_letsACallQueueInsteadOfFailingFast() throws Exception {
		MockServerTestApi api = RIP.getClient(MockServerTestApi.class,
				RipClientConfig.builder().baseUrl(server.baseUrl())
						.bulkhead(BulkheadConfig.builder().maxConcurrentCalls(1).maxWaitDuration(Duration.ofSeconds(5))
								.build())
						.build());
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{\"id\":\"1\"}").delay(200));

		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<String> holding = executor.submit(() -> api.getOrder("1", "false"));
			Thread.sleep(50);

			// The holding call frees its permit ~200ms in, well within the 5s
			// maxWaitDuration - this call must wait and then succeed, proving it
			// actually queued rather than being refused immediately.
			assertEquals("{\"id\":\"1\"}", api.getOrder("2", "false"));
			assertEquals("{\"id\":\"1\"}", holding.get(5, TimeUnit.SECONDS));
			assertEquals(2, server.requestCount());
		} finally {
			executor.shutdownNow();
		}
	}

}
