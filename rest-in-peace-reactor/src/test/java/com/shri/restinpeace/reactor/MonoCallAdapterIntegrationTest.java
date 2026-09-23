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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import kong.unirest.JsonObjectMapper;

import com.shri.restinpeace.RIP;
import com.shri.restinpeace.RipResponse;
import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.exception.RestInPeaceException;
import com.shri.restinpeace.exception.RestInPeaceHttpException;
import com.shri.restinpeace.mock.MockRestServer;
import com.shri.restinpeace.mock.MockResponse;

/**
 * {@link MonoCallAdapterFactory} dispatch against a real
 * {@link MockRestServer}, via {@link ReactorTestApi} - proves a
 * {@code Mono<T>}-returning call is dispatched exactly once, through the
 * identical retry/pipeline every other call uses, and that eagerness,
 * error propagation, and cancellation all behave as
 * {@code docs/design/reactor-call-adapter.md} §6/§6.1/§6.2 describe.
 */
class MonoCallAdapterIntegrationTest {

	private MockRestServer server;
	private ReactorTestApi api;

	@BeforeEach
	void setUp() {
		RIP.setObjectMapper(new JsonObjectMapper());
		server = MockRestServer.start();
		RestInPeaceReactor.register();
		api = RIP.getClient(ReactorTestApi.class, server.baseUrl());
	}

	@AfterEach
	void tearDown() {
		server.close();
		RestInPeaceReactor.unregister();
	}

	@Test
	void getOrder_decodesThroughTheMonoAdapter() {
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("shipped"));

		StepVerifier.create(api.getOrder("42")).expectNext("shipped").verifyComplete();
		assertEquals(1, server.countOf(HTTPMethod.GET, "/orders/{id}"));
	}

	@Test
	void getOrderWithResponse_decodesRipResponseInnerType() {
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("shipped"));

		StepVerifier.create(api.getOrderWithResponse("42"))
				.expectNextMatches(response -> response.getStatus() == 200 && "shipped".equals(response.getBody()))
				.verifyComplete();
	}

	@Test
	void downloadReport_decodesBytes() {
		server.on(HTTPMethod.GET, "/reports/{id}", MockResponse.ok("pdf-bytes"));

		StepVerifier.create(api.downloadReport("42"))
				.expectNextMatches(bytes -> "pdf-bytes".equals(new String(bytes)))
				.verifyComplete();
	}

	@Test
	void fireEvent_completesEmptyForMonoVoid() {
		server.on(HTTPMethod.POST, "/events", MockResponse.noContent());

		StepVerifier.create(api.fireEvent("order.shipped")).verifyComplete();
		assertEquals(1, server.countOf(HTTPMethod.POST, "/events"));
	}

	@Test
	void createOrderWithRetry_retriesThroughTheAdapter_sameAsAnyOtherCall() {
		server.onFlaky(HTTPMethod.POST, "/orders", 2, MockResponse.status(503, "down"), MockResponse.ok("created"));

		StepVerifier.create(api.createOrderWithRetry("payload")).expectNext("created").verifyComplete();
		assertEquals(3, server.countOf(HTTPMethod.POST, "/orders"));
	}

	@Test
	void getOrder_propagatesHttpExceptionAsMonoError() {
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.status(404, "not found"));

		StepVerifier.create(api.getOrder("missing")).expectErrorMatches(
				ex -> ex instanceof RestInPeaceHttpException && ((RestInPeaceHttpException) ex).getStatus() == 404)
				.verify();
	}

	@Test
	void getOrder_isEagerNotDeferred() throws InterruptedException {
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("shipped"));

		Mono<String> mono = api.getOrder("42"); // dispatched here, before any subscribe() at all
		Thread.sleep(100); // let Unirest's genuinely-async I/O actually land on the server
		assertEquals(1, server.countOf(HTTPMethod.GET, "/orders/{id}"));

		StepVerifier.create(mono).expectNext("shipped").verifyComplete();
		assertEquals(1, server.countOf(HTTPMethod.GET, "/orders/{id}")); // subscribing didn't dispatch a second call
	}

	@Test
	void disposing_stopsDeliveryOfAnySignalThatArrivesAfter() throws InterruptedException {
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("shipped").delay(300));
		AtomicBoolean signalReceivedAfterDispose = new AtomicBoolean(false);

		Disposable disposable = api.getOrder("42")
				.doOnNext(value -> signalReceivedAfterDispose.set(true))
				.doOnError(error -> signalReceivedAfterDispose.set(true))
				.subscribe();
		disposable.dispose(); // well before the server's own 300ms delay elapses

		Thread.sleep(500); // past the delay - if the subscription were still live, the value would have arrived by now
		assertFalse(signalReceivedAfterDispose.get());
	}

	@Test
	void validate_rawMono_fallsThroughToTheChunk2Denylist() {
		RestInPeaceException exception = assertThrows(RestInPeaceException.class,
				() -> RIP.getClient(RawMonoTestApi.class, server.baseUrl()));
		assertTrue(exception.getMessage().contains("failed during validation"));
	}

	@RestClient
	private interface RawMonoTestApi {
		@GET("/orders/{id}")
		@SuppressWarnings("rawtypes")
		Mono getOrder(@PathParam("id") String id);
	}

}
