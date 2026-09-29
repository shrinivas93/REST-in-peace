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
	void get_declinesARawTestBoxReturnTypeInsteadOfThrowing() {
		RIP.addCallAdapterFactory(TestCallAdapterFactory.INSTANCE);

		assertDoesNotThrow(() -> RIP.getClient(RawTestBoxTestApi.class, server.baseUrl()));
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

	/**
	 * Directly counts how many times a registered factory's own
	 * {@code get(Method)} is consulted, rather than going through
	 * {@code removeCallAdapterFactory} to infer registry size indirectly:
	 * that method's own {@code removeIf} removes every identity match in one
	 * call, so a single remove() would fully unregister a wrongly-duplicated
	 * entry exactly the same as a correctly-deduplicated one - a test built
	 * around "one remove() should already clear it" can never fail either
	 * way, and doesn't actually prove {@code addCallAdapterFactory}
	 * deduplicated anything. A factory that always declines forces
	 * {@code resolveCallAdapter}'s loop to consult every registered entry
	 * (a claiming factory would let the loop return after the first match,
	 * hiding a duplicate the same way), so the invocation count is a direct
	 * read of how many copies are actually in the registry.
	 */
	@Test
	void addCallAdapterFactory_registeringTheSameInstanceTwiceIsANoOp() throws NoSuchMethodException {
		java.util.concurrent.atomic.AtomicInteger consultedCount = new java.util.concurrent.atomic.AtomicInteger();
		CallAdapterFactory decliningFactory = method -> {
			consultedCount.incrementAndGet();
			return Optional.empty();
		};
		RIP.addCallAdapterFactory(decliningFactory);
		RIP.addCallAdapterFactory(decliningFactory);

		com.shri.restinpeace.internal.RequestExecutor
				.resolveCallAdapter(CallAdapterTestApi.class.getMethod("getOrder", String.class));

		assertEquals(1, consultedCount.get());
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
		EqualByTagCallAdapterFactory first = new EqualByTagCallAdapterFactory("shared-tag", "first");
		EqualByTagCallAdapterFactory second = new EqualByTagCallAdapterFactory("shared-tag", "second");
		assertEquals(first, second); // distinct instances, but .equals() by tag
		RIP.addCallAdapterFactory(first);
		RIP.addCallAdapterFactory(second);
		CallAdapterTestApi api = RIP.getClient(CallAdapterTestApi.class, server.baseUrl());
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("shipped"));

		// Removing the SECOND-registered instance, not the first, is the
		// discriminating case: List.remove(Object)'s equals()-based scan
		// would find "first" (registered earlier, so encountered first) and
		// remove that one instead, leaving "second" behind - the marker in
		// the decoded value is how the test tells which one actually
		// survived.
		RIP.removeCallAdapterFactory(second);

		assertEquals("first:shipped", api.getOrder("42").get());
	}

	@Test
	void addCallAdapterFactory_registeringTwoEqualButDistinctInstancesKeepsBoth() {
		// The add-side counterpart to the remove-side test above: an
		// equals()-based addIfAbsent() would have silently dropped "second"
		// here (it .equals() the already-registered "first"), so removing
		// "first" afterward would leave nothing registered and this call
		// would fail/fall back instead of being answered by "second".
		EqualByTagCallAdapterFactory first = new EqualByTagCallAdapterFactory("shared-tag", "first");
		EqualByTagCallAdapterFactory second = new EqualByTagCallAdapterFactory("shared-tag", "second");
		assertEquals(first, second); // distinct instances, but .equals() by tag
		RIP.addCallAdapterFactory(first);
		RIP.addCallAdapterFactory(second);
		CallAdapterTestApi api = RIP.getClient(CallAdapterTestApi.class, server.baseUrl());
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("shipped"));

		RIP.removeCallAdapterFactory(first);

		assertEquals("second:shipped", api.getOrder("42").get());
	}

	/**
	 * A {@link CallAdapterFactory} that claims every {@link TestBox}-returning
	 * method like {@link TestCallAdapterFactory}, but overrides
	 * {@code equals()}/{@code hashCode()} by {@code tag} alone, so two
	 * distinct instances constructed with the same tag compare equal despite
	 * being different objects - proving factory registration/removal is
	 * identity-based, not {@code equals()}-based. {@code marker} plays no
	 * part in equality - it's stamped onto the decoded value purely so a
	 * test can observe which of two equal-but-distinct instances actually
	 * answered.
	 */
	private static final class EqualByTagCallAdapterFactory implements CallAdapterFactory {

		private final String tag;
		private final String marker;

		EqualByTagCallAdapterFactory(String tag, String marker) {
			this.tag = tag;
			this.marker = marker;
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
					return TestBox.of(marker + ":" + delegate.join());
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
