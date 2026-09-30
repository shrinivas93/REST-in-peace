package com.shri.restinpeace.mock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import kong.unirest.JsonObjectMapper;

import com.shri.restinpeace.Page;
import com.shri.restinpeace.PaginatedCallAdapter;
import com.shri.restinpeace.PaginatedCallAdapterFactory;
import com.shri.restinpeace.RIP;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.exception.RestInPeaceException;

/**
 * {@link com.shri.restinpeace.PaginatedCallAdapter} dispatch (see
 * {@code docs/design/reactor-call-adapter.md} §7.2) against a real
 * {@link MockRestServer}, via {@link PaginatedCallAdapterTestApi}/
 * {@link TestBox}/{@link TestPaginatedCallAdapterFactory} - proving
 * registration/resolution/removal/clear all work the same way
 * {@link CallAdapterIntegrationTest} already proves for the general,
 * non-paginated {@code CallAdapterFactory} registry.
 */
class PaginatedCallAdapterIntegrationTest {

	private MockRestServer server;

	@BeforeEach
	void setUp() {
		RIP.setObjectMapper(new JsonObjectMapper());
		server = MockRestServer.start();
	}

	@AfterEach
	void tearDown() {
		server.close();
		RIP.clearPaginatedCallAdapterFactories();
	}

	@Test
	void fluxOrders_decodesThroughTheRegisteredAdapter() {
		RIP.addPaginatedCallAdapterFactory(TestPaginatedCallAdapterFactory.INSTANCE);
		PaginatedCallAdapterTestApi api = RIP.getClient(PaginatedCallAdapterTestApi.class, server.baseUrl());
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("{\"orders\":[{\"id\":\"1\"}]}"));

		@SuppressWarnings("unchecked")
		TestBox<List<PaginationTestApi.Order>> result = (TestBox<List<PaginationTestApi.Order>>) (TestBox<?>) api
				.fluxOrders(null);

		assertEquals("1", result.get().get(0).id);
		assertEquals(1, server.countOf(HTTPMethod.GET, "/orders"));
	}

	@Test
	void firstRegisteredFactoryToClaimTheMethod_wins() {
		PaginatedCallAdapterFactory taggingFirst = taggingFactory("first");
		PaginatedCallAdapterFactory taggingSecond = taggingFactory("second");
		RIP.addPaginatedCallAdapterFactory(taggingFirst);
		RIP.addPaginatedCallAdapterFactory(taggingSecond);
		PaginatedCallAdapterTestApi api = RIP.getClient(PaginatedCallAdapterTestApi.class, server.baseUrl());
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("{\"orders\":[{\"id\":\"1\"}]}"));

		@SuppressWarnings("unchecked")
		TestBox<String> result = (TestBox<String>) (TestBox<?>) api.fluxOrders(null);
		assertEquals("first", result.get());
	}

	@Test
	void aDecliningFactory_letsTheNextRegisteredFactoryAnswer() {
		RIP.addPaginatedCallAdapterFactory(method -> Optional.empty());
		RIP.addPaginatedCallAdapterFactory(TestPaginatedCallAdapterFactory.INSTANCE);
		PaginatedCallAdapterTestApi api = RIP.getClient(PaginatedCallAdapterTestApi.class, server.baseUrl());
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("{\"orders\":[{\"id\":\"1\"}]}"));

		// getClient() succeeding only proves SOME factory claims the method -
		// the actual regression this name promises is that dispatch itself
		// skips the declining factory and reaches the one that actually
		// answers, which only a real invocation can show.
		@SuppressWarnings("unchecked")
		TestBox<List<PaginationTestApi.Order>> result = (TestBox<List<PaginationTestApi.Order>>) (TestBox<?>) api
				.fluxOrders(null);

		assertEquals("1", result.get().get(0).id);
	}

	@Test
	void removingTheOnlyClaimingFactory_revertsToUnclaimedValidationFailure() {
		RIP.addPaginatedCallAdapterFactory(TestPaginatedCallAdapterFactory.INSTANCE);
		assertDoesNotThrow(() -> RIP.getClient(PaginatedCallAdapterTestApi.class, server.baseUrl()));

		RIP.removePaginatedCallAdapterFactory(TestPaginatedCallAdapterFactory.INSTANCE);

		assertThrows(RestInPeaceException.class,
				() -> RIP.getClient(PaginatedCallAdapterTestApi.class, server.baseUrl()));
	}

	@Test
	void clearingEveryFactory_revertsToUnclaimedValidationFailure() {
		RIP.addPaginatedCallAdapterFactory(TestPaginatedCallAdapterFactory.INSTANCE);
		assertDoesNotThrow(() -> RIP.getClient(PaginatedCallAdapterTestApi.class, server.baseUrl()));

		RIP.clearPaginatedCallAdapterFactories();

		assertThrows(RestInPeaceException.class,
				() -> RIP.getClient(PaginatedCallAdapterTestApi.class, server.baseUrl()));
	}

	/**
	 * Directly counts how many times a registered factory's own
	 * {@code get(Method)} is consulted, rather than going through
	 * {@code removePaginatedCallAdapterFactory} to infer registry size
	 * indirectly: that method's own {@code removeIf} removes every identity
	 * match in one call, so a single remove() would fully unregister a
	 * wrongly-duplicated entry exactly the same as a correctly-deduplicated
	 * one - a test built around "one remove() should already clear it" can
	 * never fail either way, and doesn't actually prove
	 * {@code addPaginatedCallAdapterFactory} deduplicated anything (same
	 * reasoning as {@code CallAdapterIntegrationTest}'s own identically-fixed
	 * test). A factory that always declines forces
	 * {@code RequestExecutor.resolvePaginatedCallAdapter}'s loop to consult
	 * every registered entry, so the invocation count directly reads how
	 * many copies are actually in the registry.
	 */
	@Test
	void addPaginatedCallAdapterFactory_registeringTheSameInstanceTwiceIsANoOp() throws NoSuchMethodException {
		AtomicInteger consultedCount = new AtomicInteger();
		PaginatedCallAdapterFactory decliningFactory = method -> {
			consultedCount.incrementAndGet();
			return Optional.empty();
		};
		RIP.addPaginatedCallAdapterFactory(decliningFactory);
		RIP.addPaginatedCallAdapterFactory(decliningFactory);

		com.shri.restinpeace.internal.RequestExecutor.resolvePaginatedCallAdapter(
				PaginatedCallAdapterTestApi.class.getMethod("fluxOrders", String.class));

		assertEquals(1, consultedCount.get());
	}

	/**
	 * Same reasoning as {@code CallAdapterIntegrationTest}'s own identically-named
	 * concurrent test: registers the same factory instance from two threads
	 * racing a shared barrier, the only way to actually exercise the TOCTOU
	 * window {@code addPaginatedCallAdapterFactory}'s synchronized
	 * check-then-add closes - the sequential calls above always see the
	 * first call's add already completed before the second call's own check
	 * runs. Repeated many times since this race window isn't guaranteed to
	 * be hit on any single attempt. The barrier wait and the join both
	 * carry a bounded timeout, and the worker threads are daemons, so a
	 * thread that can't start fails this test loudly instead of hanging
	 * the whole run until the CI job's own timeout kills it.
	 */
	@Test
	void addPaginatedCallAdapterFactory_concurrentRegistrationOfTheSameInstanceStillDeduplicates() throws Exception {
		Method method = PaginatedCallAdapterTestApi.class.getMethod("fluxOrders", String.class);
		for (int i = 0; i < 200; i++) {
			RIP.clearPaginatedCallAdapterFactories();
			AtomicInteger consultedCount = new AtomicInteger();
			PaginatedCallAdapterFactory decliningFactory = adapterMethod -> {
				consultedCount.incrementAndGet();
				return Optional.empty();
			};
			CyclicBarrier barrier = new CyclicBarrier(2);
			Runnable register = () -> {
				try {
					barrier.await(5, TimeUnit.SECONDS);
				} catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
					throw new RuntimeException(e);
				}
				RIP.addPaginatedCallAdapterFactory(decliningFactory);
			};
			Thread first = new Thread(register);
			Thread second = new Thread(register);
			first.setDaemon(true);
			second.setDaemon(true);
			first.start();
			second.start();
			first.join(5000);
			second.join(5000);
			assertFalse(first.isAlive() || second.isAlive(), "a worker thread didn't finish within the timeout");

			com.shri.restinpeace.internal.RequestExecutor.resolvePaginatedCallAdapter(method);

			assertEquals(1, consultedCount.get(), "duplicate registration on iteration " + i);
		}
	}

	@Test
	void removePaginatedCallAdapterFactory_removesOnlyTheInstanceByReferenceNotByEquals() {
		EqualByTagPaginatedCallAdapterFactory first = new EqualByTagPaginatedCallAdapterFactory("shared-tag", "first");
		EqualByTagPaginatedCallAdapterFactory second = new EqualByTagPaginatedCallAdapterFactory("shared-tag", "second");
		assertEquals(first, second); // distinct instances, but .equals() by tag
		RIP.addPaginatedCallAdapterFactory(first);
		RIP.addPaginatedCallAdapterFactory(second);
		PaginatedCallAdapterTestApi api = RIP.getClient(PaginatedCallAdapterTestApi.class, server.baseUrl());
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("{\"orders\":[{\"id\":\"1\"}]}"));

		// Removing the SECOND-registered instance, not the first, is the
		// discriminating case: List.remove(Object)'s equals()-based scan
		// would find "first" (registered earlier, so encountered first) and
		// remove that one instead, leaving "second" behind - the marker in
		// the decoded value is how the test tells which one actually
		// survived.
		RIP.removePaginatedCallAdapterFactory(second);

		@SuppressWarnings("unchecked")
		TestBox<String> result = (TestBox<String>) (TestBox<?>) api.fluxOrders(null);
		assertEquals("first", result.get());
	}

	@Test
	void addPaginatedCallAdapterFactory_registeringTwoEqualButDistinctInstancesKeepsBoth() {
		// The add-side counterpart to the remove-side test above: an
		// equals()-based addIfAbsent() would have silently dropped "second"
		// here (it .equals() the already-registered "first"), so removing
		// "first" afterward would leave nothing registered and this call
		// would fail/fall back instead of being answered by "second".
		EqualByTagPaginatedCallAdapterFactory first = new EqualByTagPaginatedCallAdapterFactory("shared-tag", "first");
		EqualByTagPaginatedCallAdapterFactory second = new EqualByTagPaginatedCallAdapterFactory("shared-tag", "second");
		assertEquals(first, second); // distinct instances, but .equals() by tag
		RIP.addPaginatedCallAdapterFactory(first);
		RIP.addPaginatedCallAdapterFactory(second);
		PaginatedCallAdapterTestApi api = RIP.getClient(PaginatedCallAdapterTestApi.class, server.baseUrl());
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("{\"orders\":[{\"id\":\"1\"}]}"));

		RIP.removePaginatedCallAdapterFactory(first);

		@SuppressWarnings("unchecked")
		TestBox<String> result = (TestBox<String>) (TestBox<?>) api.fluxOrders(null);
		assertEquals("second", result.get());
	}

	/**
	 * A {@link PaginatedCallAdapterFactory} that claims every {@link TestBox}-returning
	 * method like {@link TestPaginatedCallAdapterFactory}, but overrides
	 * {@code equals()}/{@code hashCode()} by {@code tag} alone, so two
	 * distinct instances constructed with the same tag compare equal despite
	 * being different objects - proving factory registration/removal is
	 * identity-based, not {@code equals()}-based. {@code marker} plays no
	 * part in equality - it's returned as the decoded value purely so a test
	 * can observe which of two equal-but-distinct instances actually
	 * answered.
	 */
	private static final class EqualByTagPaginatedCallAdapterFactory implements PaginatedCallAdapterFactory {

		private final String tag;
		private final String marker;

		EqualByTagPaginatedCallAdapterFactory(String tag, String marker) {
			this.tag = tag;
			this.marker = marker;
		}

		@Override
		public Optional<PaginatedCallAdapter<?>> get(Method method) {
			if (method.getReturnType() != TestBox.class) {
				return Optional.empty();
			}
			PaginatedCallAdapter<TestBox<String>> adapter = (Supplier<Page<Object>> firstPageSupplier) -> {
				firstPageSupplier.get();
				return TestBox.of(marker);
			};
			return Optional.of(adapter);
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof EqualByTagPaginatedCallAdapterFactory
					&& tag.equals(((EqualByTagPaginatedCallAdapterFactory) other).tag);
		}

		@Override
		public int hashCode() {
			return tag.hashCode();
		}

	}

	/**
	 * A {@link PaginatedCallAdapterFactory} that claims every {@link TestBox}-returning
	 * method exactly like {@link TestPaginatedCallAdapterFactory}, but returns {@code tag}
	 * itself instead of the fetched page's items - lets a test tell which of two
	 * registered factories actually answered.
	 */
	private static PaginatedCallAdapterFactory taggingFactory(String tag) {
		return method -> {
			if (method.getReturnType() != TestBox.class) {
				return Optional.empty();
			}
			PaginatedCallAdapter<TestBox<String>> adapter = (Supplier<Page<Object>> firstPageSupplier) -> {
				firstPageSupplier.get();
				return TestBox.of(tag);
			};
			return Optional.of(adapter);
		};
	}

}
