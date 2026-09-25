package com.shri.restinpeace.mock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
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
		CallAdapterFactory monoFactory = monoClaimingFactory();
		RIP.addCallAdapterFactory(monoFactory);
		assertDoesNotThrow(() -> RIP.getClient(CallAdapterMonoTestApi.class, server.baseUrl()));

		RIP.removeCallAdapterFactory(monoFactory);

		assertThrows(RestInPeaceException.class, () -> RIP.getClient(CallAdapterMonoTestApi.class, server.baseUrl()));
	}

	@Test
	void addCallAdapterFactory_registeringTheSameInstanceTwiceIsANoOp() {
		// Mono<T> (denylisted, §8.1) rather than TestBox<T>: TestBox isn't on
		// KNOWN_UNSUPPORTED_REACTIVE_TYPES, so getClient() succeeds whether or
		// not anything claims it - only a genuinely unclaimed denylisted type
		// makes validation fail, which is what proves the removal actually
		// unregistered every copy, not just one.
		CallAdapterFactory monoFactory = monoClaimingFactory();
		RIP.addCallAdapterFactory(monoFactory);
		RIP.addCallAdapterFactory(monoFactory);

		// A single remove() fully unregisters it - if the second add() had
		// appended a duplicate entry, one remove() would leave the other
		// behind and validation would still succeed instead of falling back.
		RIP.removeCallAdapterFactory(monoFactory);

		assertThrows(RestInPeaceException.class, () -> RIP.getClient(CallAdapterMonoTestApi.class, server.baseUrl()));
	}

	/** Claims every {@code Mono<T>}-returning method - {@code Mono} is denylisted (§8.1) when unclaimed. */
	private static CallAdapterFactory monoClaimingFactory() {
		return method -> method.getReturnType() == reactor.core.publisher.Mono.class
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
	}

	@Test
	void removeCallAdapterFactory_removesOnlyTheInstanceByReferenceNotByEquals() {
		EqualByTagCallAdapterFactory first = new EqualByTagCallAdapterFactory("shared-tag");
		EqualByTagCallAdapterFactory second = new EqualByTagCallAdapterFactory("shared-tag");
		assertEquals(first, second); // distinct instances, but .equals() by tag
		RIP.addCallAdapterFactory(first);
		RIP.addCallAdapterFactory(second);
		CallAdapterTestApi api = RIP.getClient(CallAdapterTestApi.class, server.baseUrl());
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("shipped"));

		RIP.removeCallAdapterFactory(first);

		// second is still registered by reference - equals()-based removal
		// would have removed whichever of the two a List.remove(Object) scan
		// happened to find first, which could have been either one.
		assertEquals("shipped", api.getOrder("42").get());
	}

	/**
	 * A {@link CallAdapterFactory} that claims every {@link TestBox}-returning
	 * method like {@link TestCallAdapterFactory}, but overrides
	 * {@code equals()}/{@code hashCode()} by {@code tag} alone, so two
	 * distinct instances constructed with the same tag compare equal despite
	 * being different objects - proving factory registration/removal is
	 * identity-based, not {@code equals()}-based.
	 */
	private static final class EqualByTagCallAdapterFactory implements CallAdapterFactory {

		private final String tag;

		EqualByTagCallAdapterFactory(String tag) {
			this.tag = tag;
		}

		@Override
		public Optional<CallAdapter<?>> get(Method method) {
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
					return TestBox.of(delegate.join());
				}
			});
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof EqualByTagCallAdapterFactory && tag.equals(((EqualByTagCallAdapterFactory) other).tag);
		}

		@Override
		public int hashCode() {
			return tag.hashCode();
		}

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
