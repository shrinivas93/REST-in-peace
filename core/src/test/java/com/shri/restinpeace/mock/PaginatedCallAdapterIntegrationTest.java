package com.shri.restinpeace.mock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
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

		assertDoesNotThrow(() -> RIP.getClient(PaginatedCallAdapterTestApi.class, server.baseUrl()));
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

	@Test
	void addPaginatedCallAdapterFactory_registeringTheSameInstanceTwiceIsANoOp() {
		RIP.addPaginatedCallAdapterFactory(TestPaginatedCallAdapterFactory.INSTANCE);
		RIP.addPaginatedCallAdapterFactory(TestPaginatedCallAdapterFactory.INSTANCE);

		// A single remove() fully unregisters it - if the second add() had
		// appended a duplicate entry, one remove() would leave the other
		// behind and dispatch would still succeed instead of falling back.
		RIP.removePaginatedCallAdapterFactory(TestPaginatedCallAdapterFactory.INSTANCE);

		assertThrows(RestInPeaceException.class,
				() -> RIP.getClient(PaginatedCallAdapterTestApi.class, server.baseUrl()));
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
