package com.shri.restinpeace.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import kong.unirest.JsonObjectMapper;

import com.shri.restinpeace.Page;
import com.shri.restinpeace.RIP;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.exception.RestInPeaceException;
import com.shri.restinpeace.exception.RestInPeaceHttpException;
import com.shri.restinpeace.mock.PaginationTestApi.Order;

/**
 * {@code @Paginated}/{@code Page<T>} against a real {@link MockRestServer} -
 * the chunk-2-supported shapes: a {@code VALUE} query-param cursor, a
 * {@code FULL_URL} pointer, and the {@code hasMore}/{@code total}
 * termination precedence (§6.5 of {@code docs/design/pagination-helper.md}).
 * Every {@code @Paginated} method falls back to the reflective proxy (its
 * {@code Page<T>} return type disqualifies compile-time codegen, the same
 * E9 pattern a raw {@code List<User>} return type already gets), so this
 * exercises {@code RequestExecutor.processPaginatedRequest}/
 * {@code executePageFetch} for real.
 */
class PaginationIntegrationTest {

	private MockRestServer server;
	private PaginationTestApi api;

	@BeforeEach
	void setUp() {
		RIP.setObjectMapper(new JsonObjectMapper());
		server = MockRestServer.start();
		api = RIP.getClient(PaginationTestApi.class, server.baseUrl());
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	@Test
	void valueQueryCursor_fetchesEveryPageThenTerminatesOnPointerAbsence() {
		server.on(HTTPMethod.GET, "/orders", queryParams("cursor", "page2tok"),
				MockResponse.ok("{\"orders\":[{\"id\":\"3\"}]}"));
		server.on(HTTPMethod.GET, "/orders",
				MockResponse.ok("{\"orders\":[{\"id\":\"1\"},{\"id\":\"2\"}],\"next_cursor\":\"page2tok\"}"));

		Page<Order> page1 = api.listOrdersByCursor(null);
		assertEquals(2, page1.items().size());
		assertEquals("1", page1.items().get(0).id);
		assertEquals("2", page1.items().get(1).id);
		assertTrue(page1.hasNext());

		Page<Order> page2 = page1.next();
		assertEquals(1, page2.items().size());
		assertEquals("3", page2.items().get(0).id);
		assertFalse(page2.hasNext());

		assertThrows(RestInPeaceException.class, page2::next);
		assertEquals(2, server.requestCount());
	}

	@Test
	void fullUrlPointer_followsTheExtractedNextUrlVerbatim() {
		server.on(HTTPMethod.GET, "/orders", queryParams("page", "2"),
				MockResponse.ok("{\"orders\":[{\"id\":\"2\"}]}"));
		server.on(HTTPMethod.GET, "/orders",
				MockResponse.ok("{\"orders\":[{\"id\":\"1\"}],\"next\":\"" + server.baseUrl() + "/orders?page=2\"}"));

		Page<Order> page1 = api.listOrdersByFullUrl();
		assertEquals(1, page1.items().size());
		assertTrue(page1.hasNext());

		Page<Order> page2 = page1.next();
		assertEquals(1, page2.items().size());
		assertEquals("2", page2.items().get(0).id);
		assertFalse(page2.hasNext());
	}

	@Test
	void hasMoreSignal_isAuthoritativeOverAStillPresentPointer() {
		// Stripe-shaped: the pointer stays populated even on the last page - hasMore
		// is what a consumer must trust, not "is the cursor field present."
		server.on(HTTPMethod.GET, "/orders", queryParams("cursor", "tok"),
				MockResponse.ok("{\"orders\":[{\"id\":\"2\"}],\"has_more\":false,\"next_cursor\":\"tok-again\"}"));
		server.on(HTTPMethod.GET, "/orders",
				MockResponse.ok("{\"orders\":[{\"id\":\"1\"}],\"has_more\":true,\"next_cursor\":\"tok\"}"));

		Page<Order> page1 = api.listOrdersWithHasMore(null);
		assertTrue(page1.hasNext());

		Page<Order> page2 = page1.next();
		assertFalse(page2.hasNext(), "has_more:false must win even though next_cursor is still present");
	}

	@Test
	void totalSignal_overridesThePointerAbsenceFallback() {
		// Page 1 has no next_cursor field at all - the pointer-presence fallback
		// alone would say "stop here" - but totalSource says 2 fetched < 3 total,
		// which must win, per the termination precedence (§6.5).
		server.on(HTTPMethod.GET, "/orders", queryParams("cursor", "tok"),
				MockResponse.ok("{\"orders\":[{\"id\":\"3\"}]}").header("X-Total-Count", "3"));
		server.on(HTTPMethod.GET, "/orders",
				MockResponse.ok("{\"orders\":[{\"id\":\"1\"},{\"id\":\"2\"}],\"next_cursor\":\"tok\"}")
						.header("X-Total-Count", "3"));

		Page<Order> page1 = api.listOrdersWithTotalHeader(null);
		assertTrue(page1.hasNext(), "2 fetched < 3 total must keep going even with no pointer in the body");

		Page<Order> page2 = page1.next();
		assertFalse(page2.hasNext(), "3 fetched == 3 total must stop");
	}

	@Test
	void emptyPage_stopsRegardlessOfHasMore() {
		// The unconditional safety net (§6.5 step 5): a server bug (stale
		// has_more: true on a genuinely empty page) must not spin forever.
		server.on(HTTPMethod.GET, "/orders",
				MockResponse.ok("{\"orders\":[],\"has_more\":true,\"next_cursor\":\"tok\"}"));

		Page<Order> page1 = api.listOrdersWithHasMore(null);
		assertTrue(page1.items().isEmpty());
		assertFalse(page1.hasNext());
	}

	@Test
	void rawResponse_carriesThisPagesOwnStatusAndHeaders() {
		server.on(HTTPMethod.GET, "/orders",
				MockResponse.ok("{\"orders\":[{\"id\":\"1\"}]}").header("X-Request-Id", "abc"));

		Page<Order> page1 = api.listOrdersByCursor(null);
		assertEquals(200, page1.rawResponse().getStatus());
		assertEquals("abc", page1.rawResponse().getHeader("X-Request-Id"));
	}

	@Test
	void nonSuccessStatus_throwsInsteadOfExtractingAPage() {
		server.on(HTTPMethod.GET, "/orders", MockResponse.status(500, "down"));

		assertThrows(RestInPeaceHttpException.class, () -> api.listOrdersByCursor(null));
	}

	@Test
	void emptyBody_throws() {
		server.on(HTTPMethod.GET, "/orders", MockResponse.status(200, ""));

		assertThrows(RestInPeaceException.class, () -> api.listOrdersByCursor(null));
	}

	@Test
	void malformedJsonBody_throws() {
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("not json"));

		assertThrows(RestInPeaceException.class, () -> api.listOrdersByCursor(null));
	}

	@Test
	void itemsFieldNotAnArray_throws() {
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("{\"orders\":\"not-an-array\"}"));

		assertThrows(RestInPeaceException.class, () -> api.listOrdersByCursor(null));
	}

	@Test
	void hasMoreFieldNonBooleanString_isTreatedLikeFalse() {
		// Gson's JsonPrimitive.getAsBoolean() is lenient (Boolean.parseBoolean
		// semantics: anything other than "true" is false) rather than throwing -
		// documented behavior, not a defect this feature needs to guard against.
		server.on(HTTPMethod.GET, "/orders",
				MockResponse.ok("{\"orders\":[{\"id\":\"1\"}],\"has_more\":\"maybe\"}"));

		Page<Order> page1 = api.listOrdersWithHasMore(null);
		assertFalse(page1.hasNext());
	}

	@Test
	void hasMoreFieldWrongJsonShape_throws() {
		// Unlike a string (lenient), a JSON object has no boolean coercion at all -
		// Gson itself throws for this one, exercising the wrapping catch branch.
		server.on(HTTPMethod.GET, "/orders",
				MockResponse.ok("{\"orders\":[{\"id\":\"1\"}],\"has_more\":{}}"));

		assertThrows(RestInPeaceException.class, () -> api.listOrdersWithHasMore(null));
	}

	@Test
	void totalFieldNotANumber_throws() {
		server.on(HTTPMethod.GET, "/orders",
				MockResponse.ok("{\"orders\":[{\"id\":\"1\"}]}").header("X-Total-Count", "not-a-number"));

		assertThrows(RestInPeaceException.class, () -> api.listOrdersWithTotalHeader(null));
	}

	@Test
	void hasMoreTrueWithNoExtractablePointer_throws() {
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("{\"orders\":[{\"id\":\"1\"}],\"has_more\":true}"));

		RestInPeaceException exception = assertThrows(RestInPeaceException.class,
				() -> api.listOrdersWithHasMore(null));
		assertTrue(exception.getMessage().contains("indicated another page exists"));
	}

	@Test
	void intCursor_coercesTheExtractedValueAndAdvances() {
		server.on(HTTPMethod.GET, "/orders", queryParams("page", "2"),
				MockResponse.ok("{\"orders\":[{\"id\":\"2\"}]}"));
		server.on(HTTPMethod.GET, "/orders",
				MockResponse.ok("{\"orders\":[{\"id\":\"1\"}],\"next_page\":2}"));

		Page<Order> page1 = api.listOrdersByIntCursor(0);
		assertTrue(page1.hasNext());

		Page<Order> page2 = page1.next();
		assertEquals(1, page2.items().size());
		assertFalse(page2.hasNext());
	}

	@Test
	void intCursor_nonNumericExtractedValue_throws() {
		server.on(HTTPMethod.GET, "/orders",
				MockResponse.ok("{\"orders\":[{\"id\":\"1\"}],\"next_page\":\"not-a-number\"}"));

		Page<Order> page1 = api.listOrdersByIntCursor(0);
		assertThrows(RestInPeaceException.class, page1::next);
	}

	private static Map<String, String> queryParams(String name, String value) {
		Map<String, String> params = new HashMap<>();
		params.put(name, value);
		return Collections.unmodifiableMap(params);
	}

}
