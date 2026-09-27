package com.shri.restinpeace.reactor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
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
import com.shri.restinpeace.annotation.pagination.PaginationCursor;
import com.shri.restinpeace.annotation.pagination.Paginated;
import com.shri.restinpeace.annotation.request.QueryParam;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.exception.RestInPeaceHttpException;
import com.shri.restinpeace.exception.RestInPeaceValidationException;
import com.shri.restinpeace.mock.MockRestServer;
import com.shri.restinpeace.mock.MockResponse;
import com.shri.restinpeace.validator.ReflectiveRestClientValidator;

/**
 * {@link FluxPaginatedCallAdapterFactory} ("flavor 2", §7.2) dispatch
 * against a real {@link MockRestServer} - the genuinely backpressure-aware
 * {@code Flux<T>} built on {@code @Paginated}'s existing page-fetch
 * machinery. {@link #fluxOrders_fetchesOnlyAsManyPagesAsRequested} is
 * this chunk's core deliverable (§14 item 4/§11.12): proof that a page is
 * only fetched once the subscriber's own demand genuinely requires it, not
 * just that the code compiles.
 */
class FluxPaginatedCallAdapterIntegrationTest {

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
	void fluxOrders_fetchesOnlyAsManyPagesAsRequested() {
		server.onPages(HTTPMethod.GET, "/orders", MockResponse.ok("{\"orders\":[{\"id\":\"1\"}],\"next_cursor\":\"c2\"}"),
				MockResponse.ok("{\"orders\":[{\"id\":\"2\"}],\"next_cursor\":\"c3\"}"),
				MockResponse.ok("{\"orders\":[{\"id\":\"3\"}]}"));

		StepVerifier.create(api.fluxOrders(null), 1) // request exactly 1 item up front
				.expectNextCount(1)
				// Lets the adapter's own drain() genuinely finish pausing (no demand left,
				// page exhausted) before the next request arrives - without this, the two
				// request(n) deliveries can race each other (both landing before either
				// drain() call observes a demand of exactly 0), which still produces the
				// right end-to-end result but never actually exercises the "pause with no
				// demand yet" branch this test means to prove.
				.thenAwait(Duration.ofMillis(200)).thenRequest(1).expectNextCount(1).thenCancel().verify();

		assertEquals(2, server.countOf(HTTPMethod.GET, "/orders")); // page 3 never fetched - proves backpressure
	}

	@Test
	void fluxOrders_unboundedRequest_drainsEveryPageUntilComplete() {
		server.onPages(HTTPMethod.GET, "/orders", MockResponse.ok("{\"orders\":[{\"id\":\"1\"}],\"next_cursor\":\"c2\"}"),
				MockResponse.ok("{\"orders\":[{\"id\":\"2\"}],\"next_cursor\":\"c3\"}"),
				MockResponse.ok("{\"orders\":[{\"id\":\"3\"}]}"));

		StepVerifier.create(api.fluxOrders(null)).expectNextMatches(order -> "1".equals(order.id))
				.expectNextMatches(order -> "2".equals(order.id)).expectNextMatches(order -> "3".equals(order.id))
				.verifyComplete();

		assertEquals(3, server.countOf(HTTPMethod.GET, "/orders"));
	}

	@Test
	void fluxOrders_firstPageFailureThrowsSynchronously() {
		server.on(HTTPMethod.GET, "/orders", MockResponse.status(404, "not found"));

		// Not a Flux error - the first page is fetched eagerly, before any Flux
		// even exists, matching Page<T>'s own precedent for the same method shape.
		RestInPeaceHttpException exception = assertThrows(RestInPeaceHttpException.class, () -> api.fluxOrders(null));
		assertEquals(404, exception.getStatus());
	}

	@Test
	void fluxOrders_laterPageFailurePropagatesAsFluxError() {
		server.onPages(HTTPMethod.GET, "/orders", MockResponse.ok("{\"orders\":[{\"id\":\"1\"}],\"next_cursor\":\"c2\"}"),
				MockResponse.status(500, "boom"));

		StepVerifier.create(api.fluxOrders(null)).expectNextMatches(order -> "1".equals(order.id))
				.expectErrorMatches(ex -> ex instanceof RestInPeaceHttpException
						&& ((RestInPeaceHttpException) ex).getStatus() == 500)
				.verify();
	}

	@Test
	void disposingDuringAnInFlightNextPageFetch_stopsFurtherItemsFromLanding() throws InterruptedException {
		server.onPages(HTTPMethod.GET, "/orders", MockResponse.ok("{\"orders\":[{\"id\":\"1\"}],\"next_cursor\":\"c2\"}"),
				MockResponse.ok("{\"orders\":[{\"id\":\"2\"}],\"next_cursor\":\"c3\"}").delay(300),
				MockResponse.ok("{\"orders\":[{\"id\":\"3\"}]}"));
		AtomicBoolean secondItemReceived = new AtomicBoolean(false);

		Disposable disposable = api.fluxOrders(null).doOnNext(order -> {
			if ("2".equals(order.id)) {
				secondItemReceived.set(true);
			}
		}).subscribe();

		awaitRequestCount(HTTPMethod.GET, "/orders", 2); // page 2's fetch has genuinely reached the server
		disposable.dispose();

		Thread.sleep(400); // past page 2's delay - if disposal hadn't discarded it, item "2" would have arrived by now
		assertFalse(secondItemReceived.get());
		assertEquals(2, server.countOf(HTTPMethod.GET, "/orders")); // page 3 never fetched after disposal
	}

	@Test
	void disposingBeforeAnInFlightFetchFails_suppressesTheErrorInstead() throws InterruptedException {
		server.onPages(HTTPMethod.GET, "/orders", MockResponse.ok("{\"orders\":[{\"id\":\"1\"}],\"next_cursor\":\"c2\"}"),
				MockResponse.status(500, "boom").delay(300));
		AtomicBoolean errorReceived = new AtomicBoolean(false);

		Disposable disposable = api.fluxOrders(null).doOnError(error -> errorReceived.set(true)).subscribe();

		awaitRequestCount(HTTPMethod.GET, "/orders", 2); // page 2's (failing) fetch has genuinely reached the server
		disposable.dispose();

		Thread.sleep(400); // past page 2's delay - if disposal hadn't suppressed it, the error would have arrived by now
		assertFalse(errorReceived.get());
		assertEquals(2, server.countOf(HTTPMethod.GET, "/orders")); // no request retried/repeated after disposal
	}

	/**
	 * Polls {@link MockRestServer#countOf} instead of a fixed sleep, so a test
	 * proving "disposal during an in-flight fetch" only starts timing its
	 * disposal once that fetch has genuinely reached the server - a fixed
	 * sleep here would let a slow CI runner's scheduling delay make the test
	 * pass vacuously (disposing before the fetch ever started) instead of
	 * actually exercising the in-flight race it claims to.
	 */
	private void awaitRequestCount(HTTPMethod method, String pathTemplate, int expectedCount)
			throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
		while (server.countOf(method, pathTemplate) < expectedCount) {
			if (System.nanoTime() > deadline) {
				fail("Timed out waiting for " + expectedCount + " requests to " + pathTemplate + "; observed "
						+ server.countOf(method, pathTemplate));
			}
			Thread.sleep(10);
		}
	}

	@Test
	void validate_rawPaginatedFlux_reportsUnsupportedReturnType() {
		RestInPeaceValidationException exception = assertThrows(RestInPeaceValidationException.class,
				() -> ReflectiveRestClientValidator.validate(RawPaginatedFluxTestApi.class, server.baseUrl()));
		assertTrue(exception.getValidationResult().getAllErrors()
				.contains("does not return Page<T>, Stream<T>, or Iterator<T>"));
	}

	@RestClient
	private interface RawPaginatedFluxTestApi {
		@GET("/orders")
		@Paginated(itemsField = "orders", pointerField = "next_cursor")
		@SuppressWarnings("rawtypes")
		reactor.core.publisher.Flux fluxOrders(@QueryParam("cursor") @PaginationCursor String cursor);
	}

}
