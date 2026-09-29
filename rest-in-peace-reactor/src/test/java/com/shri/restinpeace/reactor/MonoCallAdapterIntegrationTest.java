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
import com.shri.restinpeace.exception.RestInPeaceValidationException;
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
		awaitRequestCount(HTTPMethod.GET, "/orders/{id}", 1); // poll instead of a fixed sleep - avoids flaking under load

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

	/**
	 * Regression coverage for the cancellation-doesn't-reach-the-retry-loop
	 * bug: previously, disposing only cancelled the decoded
	 * {@code .thenApply(...)} stage {@code RequestExecutor.processAsync}
	 * built - a derived {@link java.util.concurrent.CompletableFuture} stage,
	 * whose cancellation never reaches anything upstream on its own - leaving
	 * the already-scheduled {@code @Retry} wait to fire regardless and issue
	 * a second request the disposed subscriber never sees delivered, wasting
	 * the call. Disposing during the backoff wait must now stop that
	 * scheduled retry from ever firing.
	 */
	@Test
	void disposingDuringTheRetryBackoffWait_stopsTheScheduledRetryFromFiring() throws InterruptedException {
		server.on(HTTPMethod.POST, "/orders", MockResponse.status(503, "down"));

		Disposable disposable = api.createOrderWithSlowRetry("payload").subscribe();
		awaitRequestCount(HTTPMethod.POST, "/orders", 1); // the first attempt has landed and failed

		disposable.dispose(); // well before the 400ms @Retry backoff wait elapses

		Thread.sleep(600); // past the backoff wait - a retry would have landed by now if not cancelled
		assertEquals(1, server.countOf(HTTPMethod.POST, "/orders"));
	}

	private void awaitRequestCount(HTTPMethod method, String pathTemplate, int expectedCount) throws InterruptedException {
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

	@Test
	void validate_rawRipResponseInsideMono_rejectedByTheRealFactory() {
		// End-to-end regression for the raw-RipResponse-in-a-CallAdapter's
		// responseBodyType() gap: MonoCallAdapterFactory.get() extracts this
		// method's Mono<RipResponse> type argument verbatim (a raw RipResponse.class,
		// since the method declares no further type parameter), so this proves the
		// real factory's extraction and ReflectiveRestClientValidator's own check
		// actually catch this combination together - not just the validator in
		// isolation against a hand-supplied CallAdapter double.
		RestInPeaceException exception = assertThrows(RestInPeaceException.class,
				() -> RIP.getClient(RawRipResponseInsideMonoTestApi.class, server.baseUrl()));
		assertTrue(exception.getCause() instanceof RestInPeaceValidationException);
		assertTrue(((RestInPeaceValidationException) exception.getCause()).getValidationResult().getAllErrors()
				.contains("raw RipResponse"));
	}

	@RestClient
	@SuppressWarnings("rawtypes")
	private interface RawRipResponseInsideMonoTestApi {
		@GET("/orders/{id}")
		Mono<RipResponse> getOrder(@PathParam("id") String id);
	}

}
