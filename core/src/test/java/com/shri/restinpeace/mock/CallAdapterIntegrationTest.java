package com.shri.restinpeace.mock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Type;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import kong.unirest.JsonObjectMapper;

import com.shri.restinpeace.CallAdapter;
import com.shri.restinpeace.CallAdapterFactory;
import com.shri.restinpeace.RIP;
import com.shri.restinpeace.RipResponse;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.exception.RestInPeaceException;
import com.shri.restinpeace.exception.RestInPeaceHttpException;

/**
 * {@link com.shri.restinpeace.CallAdapter} dispatch (see
 * {@code docs/design/reactor-call-adapter.md} §8.1) against a real
 * {@link MockRestServer}, via {@link CallAdapterTestApi}/{@link TestBox}/
 * {@link TestCallAdapterFactory} - proving an adapted call is dispatched
 * exactly once, through the identical retry/pipeline every other call
 * uses, and that failures (including one not claimed by any registered
 * factory) are handled correctly.
 */
class CallAdapterIntegrationTest {

	private MockRestServer server;

	@BeforeEach
	void setUp() {
		RIP.setObjectMapper(new JsonObjectMapper());
		server = MockRestServer.start();
	}

	@AfterEach
	void tearDown() {
		server.close();
		RIP.clearCallAdapterFactories();
	}

	@Test
	void getOrder_decodesThroughTheRegisteredAdapter() {
		RIP.addCallAdapterFactory(TestCallAdapterFactory.INSTANCE);
		CallAdapterTestApi api = RIP.getClient(CallAdapterTestApi.class, server.baseUrl());
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("shipped"));

		TestBox<String> result = api.getOrder("42");

		assertEquals("shipped", result.get());
		assertEquals(1, server.countOf(HTTPMethod.GET, "/orders/{id}"));
	}

	@Test
	void getOrderWithResponse_decodesRipResponseInnerType() {
		RIP.addCallAdapterFactory(TestCallAdapterFactory.INSTANCE);
		CallAdapterTestApi api = RIP.getClient(CallAdapterTestApi.class, server.baseUrl());
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("shipped"));

		TestBox<RipResponse<String>> result = api.getOrderWithResponse("42");

		RipResponse<String> response = result.get();
		assertEquals(200, response.getStatus());
		assertEquals("shipped", response.getBody());
	}

	@Test
	void createOrderWithRetry_retriesThroughTheAdapter_sameAsAnyOtherCall() {
		RIP.addCallAdapterFactory(TestCallAdapterFactory.INSTANCE);
		CallAdapterTestApi api = RIP.getClient(CallAdapterTestApi.class, server.baseUrl());
		server.onFlaky(HTTPMethod.POST, "/orders", 2, MockResponse.status(503, "down"), MockResponse.ok("created"));

		TestBox<String> result = api.createOrderWithRetry("payload");

		assertEquals("created", result.get());
		assertEquals(3, server.countOf(HTTPMethod.POST, "/orders"));
	}

	@Test
	void getOrder_propagatesHttpExceptionThroughTheAdapter() {
		RIP.addCallAdapterFactory(TestCallAdapterFactory.INSTANCE);
		CallAdapterTestApi api = RIP.getClient(CallAdapterTestApi.class, server.baseUrl());
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.status(404, "not found"));

		TestBox<String> result = api.getOrder("missing");

		assertTrue(result.isFailed());
		RestInPeaceHttpException exception = assertThrows(RestInPeaceHttpException.class, result::get);
		assertEquals(404, exception.getStatus());
	}

	@Test
	void firstRegisteredFactoryToClaimTheMethod_wins() {
		CallAdapterFactory taggingFirst = taggingFactory("first:");
		CallAdapterFactory taggingSecond = taggingFactory("second:");
		RIP.addCallAdapterFactory(taggingFirst);
		RIP.addCallAdapterFactory(taggingSecond);
		CallAdapterTestApi api = RIP.getClient(CallAdapterTestApi.class, server.baseUrl());
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("shipped"));

		assertEquals("first:shipped", api.getOrder("42").get());
	}

	@Test
	void removingTheOnlyClaimingFactory_revertsToUnclaimedValidationFailure() {
		CallAdapterFactory monoFactory = method -> method.getReturnType() == reactor.core.publisher.Mono.class
				? Optional.of(new CallAdapter<Object>() {
					@Override
					public Type responseBodyType() {
						return String.class;
					}

					@Override
					public Object adapt(CompletableFuture<Object> delegate) {
						return new reactor.core.publisher.Mono<>();
					}
				})
				: Optional.empty();
		RIP.addCallAdapterFactory(monoFactory);
		assertDoesNotThrow(() -> RIP.getClient(CallAdapterMonoTestApi.class, server.baseUrl()));

		RIP.removeCallAdapterFactory(monoFactory);

		assertThrows(RestInPeaceException.class, () -> RIP.getClient(CallAdapterMonoTestApi.class, server.baseUrl()));
	}

	/**
	 * A {@link CallAdapterFactory} that claims every {@link TestBox}-returning
	 * method exactly like {@link TestCallAdapterFactory}, but prefixes the
	 * decoded value with {@code tag} - lets a test tell which of two
	 * registered factories actually answered.
	 */
	private static CallAdapterFactory taggingFactory(String tag) {
		return method -> {
			if (method.getReturnType() != TestBox.class) {
				return Optional.empty();
			}
			return Optional.of(new CallAdapter<TestBox<Object>>() {
				@Override
				public Type responseBodyType() {
					return String.class;
				}

				@Override
				public TestBox<Object> adapt(CompletableFuture<Object> delegate) {
					return TestBox.of(tag + delegate.join());
				}
			});
		};
	}

}
