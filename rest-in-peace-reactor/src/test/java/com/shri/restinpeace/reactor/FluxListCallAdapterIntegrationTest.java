package com.shri.restinpeace.reactor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.test.StepVerifier;

import kong.unirest.JsonObjectMapper;

import com.shri.restinpeace.RIP;
import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.exception.RestInPeaceException;
import com.shri.restinpeace.exception.RestInPeaceHttpException;
import com.shri.restinpeace.mock.MockRestServer;
import com.shri.restinpeace.mock.MockResponse;

/**
 * {@link FluxListCallAdapterFactory} ("flavor 1", §7.1) dispatch against a
 * real {@link MockRestServer} - a plain, non-{@code @Paginated}
 * {@code Flux<T>} decoding a single JSON array response, exactly like
 * {@code List<T>} already does, then emitting it item by item.
 */
class FluxListCallAdapterIntegrationTest {

	private MockRestServer server;
	private FluxTestApi api;

	@BeforeEach
	void setUp() {
		RIP.setObjectMapper(new JsonObjectMapper());
		server = MockRestServer.start();
		RestInPeaceReactor.register();
		api = RIP.getClient(FluxTestApi.class, server.baseUrl());
	}

	@AfterEach
	void tearDown() {
		server.close();
		RestInPeaceReactor.unregister();
	}

	@Test
	void listOrders_emitsEachArrayItem() {
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("[{\"id\":\"1\"},{\"id\":\"2\"}]"));

		StepVerifier.create(api.listOrders()).expectNextMatches(order -> "1".equals(order.id))
				.expectNextMatches(order -> "2".equals(order.id)).verifyComplete();
		assertEquals(1, server.countOf(HTTPMethod.GET, "/orders"));
	}

	@Test
	void listOrders_emptyArrayCompletesWithNoItems() {
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("[]"));

		StepVerifier.create(api.listOrders()).verifyComplete();
	}

	@Test
	void listOrders_propagatesHttpExceptionAsFluxError() {
		server.on(HTTPMethod.GET, "/orders", MockResponse.status(500, "boom"));

		StepVerifier.create(api.listOrders()).expectErrorMatches(
				ex -> ex instanceof RestInPeaceHttpException && ((RestInPeaceHttpException) ex).getStatus() == 500)
				.verify();
	}

	@Test
	void disposing_stopsDeliveryOfAnySignalThatArrivesAfter() throws InterruptedException {
		server.on(HTTPMethod.GET, "/orders", MockResponse.json("[{\"id\":\"1\"}]").delay(300));
		AtomicBoolean signalReceivedAfterDispose = new AtomicBoolean(false);

		Disposable disposable = api.listOrders().doOnNext(order -> signalReceivedAfterDispose.set(true))
				.doOnError(error -> signalReceivedAfterDispose.set(true)).subscribe();
		disposable.dispose();

		Thread.sleep(500);
		assertFalse(signalReceivedAfterDispose.get());
	}

	@Test
	void validate_rawFlux_fallsThroughToTheChunk2Denylist() {
		RestInPeaceException exception = assertThrows(RestInPeaceException.class,
				() -> RIP.getClient(RawFluxTestApi.class, server.baseUrl()));
		assertTrue(exception.getMessage().contains("failed during validation"));
	}

	@RestClient
	private interface RawFluxTestApi {
		@GET("/orders")
		@SuppressWarnings("rawtypes")
		reactor.core.publisher.Flux getOrders();
	}

}
