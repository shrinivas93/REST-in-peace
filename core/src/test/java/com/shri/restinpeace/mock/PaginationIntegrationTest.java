package com.shri.restinpeace.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import kong.unirest.JsonObjectMapper;

import com.shri.restinpeace.Page;
import com.shri.restinpeace.PaginationRequest;
import com.shri.restinpeace.PaginationStrategy;
import com.shri.restinpeace.RIP;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.exception.RestInPeaceException;
import com.shri.restinpeace.exception.RestInPeaceHttpException;
import com.shri.restinpeace.mock.PaginationTestApi.Order;

/**
 * {@code @Paginated} against a real {@link MockRestServer} - a {@code VALUE}
 * query-param cursor, a {@code FULL_URL} pointer, the {@code hasMore}/
 * {@code total} termination precedence (§6.5 of
 * {@code docs/design/pagination-helper.md}), and the {@code Page<T>}/
 * {@code Stream<T>}/{@code Iterator<T>} return-type-driven iteration styles
 * (§6.4). Every {@code @Paginated} method falls back to the reflective proxy
 * (its return type disqualifies compile-time codegen, the same E9 pattern a
 * raw {@code List<User>} return type already gets), so this exercises
 * {@code RequestExecutor.processPaginatedRequest}/{@code executePageFetch}
 * for real.
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

	@Test
	void longCursor_coercesTheExtractedValueAndAdvances() {
		server.on(HTTPMethod.GET, "/orders", queryParams("seq", "42"),
				MockResponse.ok("{\"orders\":[{\"id\":\"2\"}]}"));
		server.on(HTTPMethod.GET, "/orders",
				MockResponse.ok("{\"orders\":[{\"id\":\"1\"}],\"next_seq\":42}"));

		Page<Order> page1 = api.listOrdersByLongCursor(0L);
		assertTrue(page1.hasNext());

		Page<Order> page2 = page1.next();
		assertEquals(1, page2.items().size());
		assertFalse(page2.hasNext());
	}

	@Test
	void longCursor_nonNumericExtractedValue_throws() {
		server.on(HTTPMethod.GET, "/orders",
				MockResponse.ok("{\"orders\":[{\"id\":\"1\"}],\"next_seq\":\"not-a-number\"}"));

		Page<Order> page1 = api.listOrdersByLongCursor(0L);
		assertThrows(RestInPeaceException.class, page1::next);
	}

	@Test
	void nestedItemsFieldMissingIntermediateSegment_throws() {
		// itemsField = "data.orders" but the response has no "data" object at all -
		// exercises the dotted-path getPath's missing-intermediate-segment branch.
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("{\"other\":true}"));

		assertThrows(RestInPeaceException.class, () -> api.listOrdersByNestedItemsField(null));
	}

	@Test
	void nestedItemsField_resolvesThroughTheNestedPath() {
		server.on(HTTPMethod.GET, "/orders",
				MockResponse.ok("{\"data\":{\"orders\":[{\"id\":\"1\"}]}}"));

		Page<Order> page1 = api.listOrdersByNestedItemsField(null);
		assertEquals(1, page1.items().size());
		assertFalse(page1.hasNext());
	}

	@Test
	void stream_flattensEveryPageInOrder() {
		server.on(HTTPMethod.GET, "/orders", queryParams("cursor", "tok"),
				MockResponse.ok("{\"orders\":[{\"id\":\"3\"}]}"));
		server.on(HTTPMethod.GET, "/orders",
				MockResponse.ok("{\"orders\":[{\"id\":\"1\"},{\"id\":\"2\"}],\"next_cursor\":\"tok\"}"));

		Stream<Order> stream = api.streamOrders(null);
		List<String> ids = stream.map(order -> order.id).collect(Collectors.toList());

		assertEquals(Arrays.asList("1", "2", "3"), ids);
	}

	@Test
	void stream_isLazy_noRequestUntilATerminalOperation() {
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("{\"orders\":[{\"id\":\"1\"}]}"));

		Stream<Order> stream = api.streamOrders(null);
		assertEquals(0, server.requestCount(), "constructing the Stream must not make any network call yet");

		long count = stream.count();
		assertEquals(1, count);
		assertEquals(1, server.requestCount());
	}

	@Test
	void iterator_flattensEveryPageInOrder() {
		server.on(HTTPMethod.GET, "/orders", queryParams("cursor", "tok"),
				MockResponse.ok("{\"orders\":[{\"id\":\"3\"}]}"));
		server.on(HTTPMethod.GET, "/orders",
				MockResponse.ok("{\"orders\":[{\"id\":\"1\"},{\"id\":\"2\"}],\"next_cursor\":\"tok\"}"));

		Iterator<Order> iterator = api.iterateOrders(null);
		List<String> ids = new ArrayList<>();
		while (iterator.hasNext()) {
			ids.add(iterator.next().id);
		}

		assertEquals(Arrays.asList("1", "2", "3"), ids);
	}

	@Test
	void iterator_isLazy_noRequestUntilHasNext() {
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("{\"orders\":[{\"id\":\"1\"}]}"));

		Iterator<Order> iterator = api.iterateOrders(null);
		assertEquals(0, server.requestCount(), "constructing the Iterator must not make any network call yet");

		assertTrue(iterator.hasNext());
		assertEquals(1, server.requestCount());
	}

	@Test
	void iterator_exhausted_nextThrowsNoSuchElementException() {
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("{\"orders\":[{\"id\":\"1\"}]}"));

		Iterator<Order> iterator = api.iterateOrders(null);
		assertTrue(iterator.hasNext());
		iterator.next();
		assertFalse(iterator.hasNext());
		assertThrows(NoSuchElementException.class, iterator::next);
	}

	@Test
	void stream_emptyFirstPage_yieldsNoItems() {
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("{\"orders\":[]}"));

		long count = api.streamOrders(null).count();
		assertEquals(0, count);
	}

	@Test
	void bodyFieldCursor_setsTheExtractedValueIntoTheRequestBodyAndPreservesOtherFields() {
		server.on(HTTPMethod.POST, "/orders/search",
				request -> request.getBody().contains("\"cursor\":\"tok\"") && request.getBody().contains("\"query\""),
				MockResponse.ok("{\"orders\":[{\"id\":\"2\"}]}"));
		server.on(HTTPMethod.POST, "/orders/search",
				MockResponse.ok("{\"orders\":[{\"id\":\"1\"}],\"next_cursor\":\"tok\"}"));

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("query", "active");
		Page<Order> page1 = api.searchOrders(body);
		assertEquals(1, page1.items().size());
		assertTrue(page1.hasNext());

		Page<Order> page2 = page1.next();
		assertEquals(1, page2.items().size());
		assertEquals("2", page2.items().get(0).id);
		assertFalse(page2.hasNext());
		assertEquals(2, server.requestCount());
	}

	@Test
	void bodyFieldCursor_doesNotMutateTheCallersOriginalBodyMap() {
		server.on(HTTPMethod.POST, "/orders/search", request -> request.getBody().contains("\"cursor\":\"tok\""),
				MockResponse.ok("{\"orders\":[{\"id\":\"2\"}]}"));
		server.on(HTTPMethod.POST, "/orders/search",
				MockResponse.ok("{\"orders\":[{\"id\":\"1\"}],\"next_cursor\":\"tok\"}"));

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("query", "active");
		api.searchOrders(body).next();

		assertFalse(body.containsKey("cursor"), "the caller's own map must not be mutated by pagination");
		assertEquals(1, body.size());
	}

	@Test
	void bodyFieldCursor_nestedField_setsIntoTheNestedPathAndPreservesSiblingKeys() {
		server.on(HTTPMethod.POST, "/orders/search",
				request -> request.getBody().contains("\"cursor\":\"tok\"")
						&& request.getBody().contains("\"other\":\"value\""),
				MockResponse.ok("{\"orders\":[{\"id\":\"2\"}]}"));
		server.on(HTTPMethod.POST, "/orders/search",
				MockResponse.ok("{\"orders\":[{\"id\":\"1\"}],\"next_cursor\":\"tok\"}"));

		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("other", "value");
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("meta", meta);

		Page<Order> page1 = api.searchOrdersByNestedBodyField(body);
		assertTrue(page1.hasNext());

		Page<Order> page2 = page1.next();
		assertEquals(1, page2.items().size());
		assertFalse(page2.hasNext());
	}

	@Test
	void itemFieldCursor_extractsFromLastItemAndAdvances() {
		// since_id-style keyset (Twitter v1.1, row 41): no hasMore/total signal, so
		// the empty-items safety net (§6.5 step 5) is what actually terminates -
		// the last fetched item's own field is (by construction) always present
		// while items keep coming back, so pointer-presence alone never stops it.
		server.on(HTTPMethod.GET, "/orders", queryParams("since", "3"), MockResponse.ok("{\"orders\":[]}"));
		server.on(HTTPMethod.GET, "/orders", queryParams("since", "2"),
				MockResponse.ok("{\"orders\":[{\"id\":\"3\"}]}"));
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("{\"orders\":[{\"id\":\"1\"},{\"id\":\"2\"}]}"));

		Page<Order> page1 = api.listOrdersByItemFieldCursor(null);
		assertEquals(2, page1.items().size());
		assertTrue(page1.hasNext());

		Page<Order> page2 = page1.next();
		assertEquals(1, page2.items().size());
		assertEquals("3", page2.items().get(0).id);
		assertTrue(page2.hasNext());

		Page<Order> page3 = page2.next();
		assertTrue(page3.items().isEmpty());
		assertFalse(page3.hasNext());
		assertEquals(3, server.requestCount());
	}

	@Test
	void itemFieldWithHasMore_missingFieldOnLastItem_throws() {
		// Unlike the plain pointer-presence fallback above, hasMore = true makes a
		// missing field a genuine contradiction - "another page exists" but nothing
		// to extract - so this must throw rather than quietly stopping.
		server.on(HTTPMethod.GET, "/orders",
				MockResponse.ok("{\"orders\":[{\"createdAt\":\"t1\"}],\"has_more\":true}"));

		RestInPeaceException exception = assertThrows(RestInPeaceException.class,
				() -> api.listOrdersByItemFieldWithHasMore(null));
		assertTrue(exception.getMessage().contains("indicated another page exists"));
	}

	@Test
	void itemFieldCursor_emptyFirstPage_stopsWithoutExtracting() {
		// Nothing to extract from (no last item) - must stop cleanly, not throw.
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("{\"orders\":[]}"));

		Page<Order> page1 = api.listOrdersByItemFieldCursor(null);
		assertTrue(page1.items().isEmpty());
		assertFalse(page1.hasNext());
	}

	@Test
	void compositeItemFieldCursor_extractsBothFieldsAndAdvancesBothParams() {
		Map<String, String> nextPageParams = new HashMap<>();
		nextPageParams.put("lastId", "2");
		nextPageParams.put("lastTs", "t2");
		server.on(HTTPMethod.GET, "/orders", nextPageParams, MockResponse.ok("{\"orders\":[]}"));
		server.on(HTTPMethod.GET, "/orders",
				MockResponse.ok("{\"orders\":[{\"id\":\"1\",\"createdAt\":\"t1\"},"
						+ "{\"id\":\"2\",\"createdAt\":\"t2\"}]}"));

		Page<Order> page1 = api.listOrdersByCompositeItemFieldCursor(null, null);
		assertEquals(2, page1.items().size());
		assertTrue(page1.hasNext());

		Page<Order> page2 = page1.next();
		assertTrue(page2.items().isEmpty());
		assertFalse(page2.hasNext());
	}

	@Test
	void compositeItemFieldIntoBody_setsBothExtractedFieldsIntoTheBody() {
		server.on(HTTPMethod.POST, "/orders/search",
				request -> request.getBody().contains("\"lastId\":\"2\"")
						&& request.getBody().contains("\"lastTimestamp\":\"t2\""),
				MockResponse.ok("{\"orders\":[]}"));
		server.on(HTTPMethod.POST, "/orders/search",
				MockResponse.ok("{\"orders\":[{\"id\":\"1\",\"createdAt\":\"t1\"},"
						+ "{\"id\":\"2\",\"createdAt\":\"t2\"}]}"));

		Page<Order> page1 = api.searchOrdersByCompositeItemFieldIntoBody(new LinkedHashMap<>());
		assertTrue(page1.hasNext());

		Page<Order> page2 = page1.next();
		assertTrue(page2.items().isEmpty());
		assertFalse(page2.hasNext());
	}

	@Test
	void linkHeaderPointer_parsesRelNextAndFollowsItUntilTheLastPage() {
		// GitHub/Shopify-shaped RFC 8288 Link header - rel="next" drives the next
		// fetch, and the genuinely last page (only rel="prev", no rel="next") stops.
		server.on(HTTPMethod.GET, "/orders", queryParams("page", "3"),
				MockResponse.ok("[{\"id\":\"3\"}]")
						.header("Link", "<" + server.baseUrl() + "/orders?page=2>; rel=\"prev\""));
		server.on(HTTPMethod.GET, "/orders", queryParams("page", "2"),
				MockResponse.ok("[{\"id\":\"2\"}]")
						.header("Link",
								"<" + server.baseUrl() + "/orders?page=3>; rel=\"next\", <" + server.baseUrl()
										+ "/orders?page=1>; rel=\"prev\""));
		server.on(HTTPMethod.GET, "/orders",
				MockResponse.ok("[{\"id\":\"1\"}]")
						.header("Link", "<" + server.baseUrl() + "/orders?page=2>; rel=\"next\""));

		Page<Order> page1 = api.listOrdersByLinkHeader();
		assertEquals("1", page1.items().get(0).id);
		assertTrue(page1.hasNext());

		Page<Order> page2 = page1.next();
		assertEquals("2", page2.items().get(0).id);
		assertTrue(page2.hasNext());

		Page<Order> page3 = page2.next();
		assertEquals("3", page3.items().get(0).id);
		assertFalse(page3.hasNext());
	}

	@Test
	void linkHeaderPointer_multipleRelValuesOnOneLink_matchesNext() {
		// RFC 8288 allows space-separated multiple rel values on one link.
		server.on(HTTPMethod.GET, "/orders", queryParams("page", "2"), MockResponse.ok("[{\"id\":\"2\"}]"));
		server.on(HTTPMethod.GET, "/orders",
				MockResponse.ok("[{\"id\":\"1\"}]")
						.header("Link", "<" + server.baseUrl() + "/orders?page=2>; rel=\"next last\""));

		Page<Order> page1 = api.listOrdersByLinkHeader();
		assertTrue(page1.hasNext());
		assertEquals("2", page1.next().items().get(0).id);
	}

	@Test
	void linkHeaderPointer_skipsMalformedSegmentsAndFindsTheValidOne() {
		// One comma-separated segment with no "<" at all, and one with "<" but no
		// closing ">", must not break parsing of a later, well-formed segment.
		server.on(HTTPMethod.GET, "/orders", queryParams("page", "2"), MockResponse.ok("[{\"id\":\"2\"}]"));
		server.on(HTTPMethod.GET, "/orders",
				MockResponse.ok("[{\"id\":\"1\"}]").header("Link",
						"not-a-link-segment, <missing-close-bracket, <" + server.baseUrl()
								+ "/orders?page=2>; rel=\"next\""));

		Page<Order> page1 = api.listOrdersByLinkHeader();
		assertTrue(page1.hasNext());
		assertEquals("2", page1.next().items().get(0).id);
	}

	@Test
	void linkHeaderPointer_rawUrlWithoutRfc8288Formatting_isUsedVerbatim() {
		// A header that doesn't look like an RFC 8288 Link header at all (no
		// angle-bracketed URI) is used as the next URL as-is - the simpler
		// "the header's raw value already IS the next URL" case still works for a
		// non-standard header.
		server.on(HTTPMethod.GET, "/orders", queryParams("page", "2"), MockResponse.ok("[{\"id\":\"2\"}]"));
		server.on(HTTPMethod.GET, "/orders",
				MockResponse.ok("[{\"id\":\"1\"}]").header("Link", server.baseUrl() + "/orders?page=2"));

		Page<Order> page1 = api.listOrdersByLinkHeader();
		assertTrue(page1.hasNext());
		assertEquals("2", page1.next().items().get(0).id);
	}

	@Test
	void advanceOffsetWithTotal_advancesByPageSizeAndStopsWhenTotalReached() {
		// Row 6/27/29-31: no pointer at all (pointerSource = NONE) - RIP computes the
		// next offset itself from pageSize, and totalField (read like any other
		// termination signal) decides when to stop.
		server.on(HTTPMethod.GET, "/orders", queryParams("offset", "2"),
				MockResponse.ok("{\"orders\":[{\"id\":\"3\"}],\"total\":3}"));
		server.on(HTTPMethod.GET, "/orders",
				MockResponse.ok("{\"orders\":[{\"id\":\"1\"},{\"id\":\"2\"}],\"total\":3}"));

		Page<Order> page1 = api.listOrdersByOffsetWithTotal(0);
		assertEquals(2, page1.items().size());
		assertTrue(page1.hasNext(), "2 fetched < 3 total must keep going");

		Page<Order> page2 = page1.next();
		assertEquals(1, page2.items().size());
		assertEquals("3", page2.items().get(0).id);
		assertFalse(page2.hasNext(), "3 fetched == 3 total must stop");
		assertEquals(2, server.requestCount());
	}

	@Test
	void advancePageNumberWithTotalPages_advancesByOneAndStopsWhenTotalPagesReached() {
		// Row 32-34: Algolia-style page-count arithmetic - no pointer, RIP just
		// increments the page number, and totalPagesField decides when to stop.
		server.on(HTTPMethod.GET, "/orders", queryParams("page", "2"),
				MockResponse.ok("{\"orders\":[{\"id\":\"2\"}],\"totalPages\":2}"));
		server.on(HTTPMethod.GET, "/orders",
				MockResponse.ok("{\"orders\":[{\"id\":\"1\"}],\"totalPages\":2}"));

		Page<Order> page1 = api.listOrdersByPageNumberWithTotalPages(1);
		assertTrue(page1.hasNext(), "1 page fetched < 2 total pages must keep going");

		Page<Order> page2 = page1.next();
		assertEquals("2", page2.items().get(0).id);
		assertFalse(page2.hasNext(), "2 pages fetched == 2 total pages must stop");
	}

	@Test
	void advanceOffsetNoSignal_stopsOnAShortPage() {
		// Row 35/37/38: no hasMore/total/totalPages signal at all - a page shorter
		// than pageSize is, by construction, the last one (§6.5's advance-based
		// fallback).
		server.on(HTTPMethod.GET, "/orders", queryParams("offset", "2"),
				MockResponse.ok("{\"orders\":[{\"id\":\"3\"}]}"));
		server.on(HTTPMethod.GET, "/orders",
				MockResponse.ok("{\"orders\":[{\"id\":\"1\"},{\"id\":\"2\"}]}"));

		Page<Order> page1 = api.listOrdersByOffsetShortPageStop(0);
		assertEquals(2, page1.items().size());
		assertTrue(page1.hasNext(), "a full pageSize-sized page must not be assumed to be the last one");

		Page<Order> page2 = page1.next();
		assertEquals(1, page2.items().size());
		assertFalse(page2.hasNext(), "fewer items than pageSize must be treated as the last page");
	}

	@Test
	void advancePageNumberNoSignal_stopsOnAnEmptyPage() {
		// Row 39/40: page-number-only, no signal at all - falls through to the
		// unconditional empty-items safety net (§6.5 step 5) to terminate.
		server.on(HTTPMethod.GET, "/orders", queryParams("page", "2"), MockResponse.ok("{\"orders\":[]}"));
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("{\"orders\":[{\"id\":\"1\"}]}"));

		Page<Order> page1 = api.listOrdersByPageNumberNoSignal(1);
		assertTrue(page1.hasNext());

		Page<Order> page2 = page1.next();
		assertTrue(page2.items().isEmpty());
		assertFalse(page2.hasNext());
	}

	@Test
	void advanceOffsetIntoBody_writesTheComputedOffsetAsAJsonNumber() {
		// Row 29/36: an advance-computed value written into a @Body carrier is a
		// real JSON number (not a quoted string like an extracted pointer value),
		// matching what a numeric field such as Elasticsearch's from/size expects.
		server.on(HTTPMethod.POST, "/orders/search", request -> request.getBody().contains("\"offset\":2"),
				MockResponse.ok("{\"orders\":[{\"id\":\"3\"}]}"));
		server.on(HTTPMethod.POST, "/orders/search",
				MockResponse.ok("{\"orders\":[{\"id\":\"1\"},{\"id\":\"2\"}]}"));

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("query", "active");
		Page<Order> page1 = api.searchOrdersByOffsetIntoBody(body);
		assertEquals(2, page1.items().size());
		assertTrue(page1.hasNext());

		Page<Order> page2 = page1.next();
		assertEquals(1, page2.items().size());
		assertEquals("3", page2.items().get(0).id);
		assertFalse(page2.hasNext());
	}

	@Test
	void strategyQueryParam_advancesViaTheLastItemsIdUntilAShortPage() {
		server.on(HTTPMethod.GET, "/orders", queryParams("since", "2"), MockResponse.ok("[{\"id\":\"3\"}]"));
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("[{\"id\":\"1\"},{\"id\":\"2\"}]"));

		PaginationStrategy<Order> strategy = ctx -> ctx.items().size() < 2 ? Optional.empty()
				: Optional.of(PaginationRequest.withQueryParam("since", ctx.items().get(ctx.items().size() - 1).id));

		Page<Order> page1 = api.listOrdersByStrategyQueryParam(null, strategy);
		assertEquals(2, page1.items().size());
		assertTrue(page1.hasNext());

		Page<Order> page2 = page1.next();
		assertEquals(1, page2.items().size());
		assertEquals("3", page2.items().get(0).id);
		assertFalse(page2.hasNext());
	}

	@Test
	void strategyRawBody_readsTheParsedResponseBodyDirectly() {
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("[{\"id\":\"1\"},{\"id\":\"2\"}]"));

		PaginationStrategy<Order> strategy = ctx -> {
			assertEquals(2, ctx.rawBody().getAsJsonArray().size());
			return Optional.empty();
		};

		Page<Order> page1 = api.listOrdersByStrategyAlwaysContinues(strategy);
		assertEquals(2, page1.items().size());
		assertFalse(page1.hasNext());
	}

	@Test
	void strategyAnd_combinesAQueryParamAndAHeaderOverride() {
		server.on(HTTPMethod.GET, "/orders",
				request -> "2".equals(request.getQueryParam("since")) && "x".equals(request.getHeader("X-Extra")),
				MockResponse.ok("[]"));
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("[{\"id\":\"1\"},{\"id\":\"2\"}]"));

		PaginationStrategy<Order> strategy = ctx -> ctx.items().isEmpty() ? Optional.empty()
				: Optional.of(PaginationRequest.withQueryParam("since", ctx.items().get(ctx.items().size() - 1).id)
						.and(PaginationRequest.withHeader("X-Extra", "x")));

		Page<Order> page1 = api.listOrdersByStrategyQueryParam(null, strategy);
		assertTrue(page1.hasNext());

		Page<Order> page2 = page1.next();
		assertTrue(page2.items().isEmpty());
		assertFalse(page2.hasNext());
	}

	@Test
	void strategyToUrl_followsAHeaderDerivedNextUrl() {
		server.on(HTTPMethod.GET, "/orders", queryParams("page", "2"), MockResponse.ok("[{\"id\":\"2\"}]"));
		server.on(HTTPMethod.GET, "/orders",
				MockResponse.ok("[{\"id\":\"1\"}]").header("X-Next", server.baseUrl() + "/orders?page=2"));

		PaginationStrategy<Order> strategy = ctx -> Optional.ofNullable(ctx.header("X-Next"))
				.map(PaginationRequest::toUrl);

		Page<Order> page1 = api.listOrdersByStrategyFullUrl(strategy);
		assertEquals(1, page1.items().size());
		assertTrue(page1.hasNext());

		Page<Order> page2 = page1.next();
		assertEquals(1, page2.items().size());
		assertEquals("2", page2.items().get(0).id);
		assertFalse(page2.hasNext());
	}

	@Test
	void strategyWithPathParam_advancesThePageNumberInTheUrlTemplate() {
		// Every URL template placeholder must have a matching @PathParam (checked at
		// validation time for every method, pagination or not), so withPathParam
		// routes through the method's own @PathParam("page") argument rather than
		// patching the resolved URL string directly.
		server.on(HTTPMethod.GET, "/orders/2", MockResponse.ok("[{\"id\":\"2\"}]"));
		server.on(HTTPMethod.GET, "/orders/1", MockResponse.ok("[{\"id\":\"1\"}]"));

		PaginationStrategy<Order> strategy = ctx -> ctx.pagesFetchedSoFar() < 2
				? Optional.of(PaginationRequest.withPathParam("page", ctx.pagesFetchedSoFar() + 1))
				: Optional.empty();

		Page<Order> page1 = api.listOrdersByStrategyPathParam(1, strategy);
		assertEquals("1", page1.items().get(0).id);
		assertTrue(page1.hasNext());

		Page<Order> page2 = page1.next();
		assertEquals("2", page2.items().get(0).id);
		assertFalse(page2.hasNext());
	}

	@Test
	void strategyWithHeader_addsAnOffsetHeaderToTheNextRequest() {
		server.on(HTTPMethod.GET, "/orders", request -> "1".equals(request.getHeader("X-Offset")),
				MockResponse.ok("[{\"id\":\"2\"}]"));
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("[{\"id\":\"1\"}]"));

		PaginationStrategy<Order> strategy = ctx -> ctx.pagesFetchedSoFar() < 2
				? Optional.of(PaginationRequest.withHeader("X-Offset", String.valueOf(ctx.itemsFetchedSoFar())))
				: Optional.empty();

		Page<Order> page1 = api.listOrdersByStrategyHeader(strategy);
		assertTrue(page1.hasNext());

		Page<Order> page2 = page1.next();
		assertEquals("2", page2.items().get(0).id);
		assertFalse(page2.hasNext());
	}

	@Test
	void strategyWithBodyField_setsTheOffsetFieldAndPreservesOtherFields() {
		server.on(HTTPMethod.POST, "/orders/search",
				request -> request.getBody().contains("\"offset\":2") && request.getBody().contains("\"query\""),
				MockResponse.ok("[{\"id\":\"3\"}]"));
		server.on(HTTPMethod.POST, "/orders/search", MockResponse.ok("[{\"id\":\"1\"},{\"id\":\"2\"}]"));

		PaginationStrategy<Order> strategy = ctx -> ctx.items().size() < 2 ? Optional.empty()
				: Optional.of(PaginationRequest.withBodyField("offset", ctx.itemsFetchedSoFar()));

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("query", "active");
		Page<Order> page1 = api.searchOrdersByStrategyBodyField(body, strategy);
		assertEquals(2, page1.items().size());
		assertTrue(page1.hasNext());

		Page<Order> page2 = page1.next();
		assertEquals(1, page2.items().size());
		assertEquals("3", page2.items().get(0).id);
		assertFalse(page2.hasNext());
	}

	@Test
	void strategyAnd_combinesTwoBodyFieldOverridesIntoOneRequest() {
		server.on(HTTPMethod.POST, "/orders/search",
				request -> request.getBody().contains("\"offset\":2") && request.getBody().contains("\"limit\":10"),
				MockResponse.ok("[{\"id\":\"3\"}]"));
		server.on(HTTPMethod.POST, "/orders/search", MockResponse.ok("[{\"id\":\"1\"},{\"id\":\"2\"}]"));

		PaginationStrategy<Order> strategy = ctx -> ctx.items().size() < 2 ? Optional.empty()
				: Optional.of(PaginationRequest.withBodyField("offset", ctx.itemsFetchedSoFar())
						.and(PaginationRequest.withBodyField("limit", 10)));

		Map<String, Object> body = new LinkedHashMap<>();
		Page<Order> page1 = api.searchOrdersByStrategyCompositeBodyField(body, strategy);
		assertTrue(page1.hasNext());

		Page<Order> page2 = page1.next();
		assertEquals("3", page2.items().get(0).id);
		assertFalse(page2.hasNext());
	}

	@Test
	void strategyBodyFieldWithNoBodyParam_throwsOnNext() {
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("[{\"id\":\"1\"}]"));
		PaginationStrategy<Order> strategy = ctx -> Optional.of(PaginationRequest.withBodyField("offset", 1));

		Page<Order> page1 = api.listOrdersByStrategyMissingBodyParam(strategy);
		assertTrue(page1.hasNext());
		assertThrows(RestInPeaceException.class, page1::next);
	}

	@Test
	void strategyPathParamWithNoMatchingParam_throwsOnNext() {
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("[{\"id\":\"1\"}]"));
		PaginationStrategy<Order> strategy = ctx -> Optional.of(PaginationRequest.withPathParam("page", 2));

		Page<Order> page1 = api.listOrdersByStrategyMissingPathParam(strategy);
		assertTrue(page1.hasNext());
		assertThrows(RestInPeaceException.class, page1::next);
	}

	@Test
	void strategyAlwaysContinuing_stopsOnAnEmptyPageRegardless() {
		// The unconditional safety net (§6.5 step 5) applies to the strategy path too -
		// even a (buggy) strategy that keeps returning a next request must not spin
		// forever once the server hands back nothing.
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("[]"));
		PaginationStrategy<Order> strategy = ctx -> Optional.of(PaginationRequest.withQueryParam("x", 1));

		Page<Order> page1 = api.listOrdersByStrategyAlwaysContinues(strategy);
		assertTrue(page1.items().isEmpty());
		assertFalse(page1.hasNext());
	}

	@Test
	void strategyNonArrayResponseBody_throws() {
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("{\"orders\":[]}"));
		PaginationStrategy<Order> strategy = ctx -> Optional.empty();

		assertThrows(RestInPeaceException.class, () -> api.listOrdersByStrategyAlwaysContinues(strategy));
	}

	@Test
	void strategyNullArgument_throws() {
		assertThrows(RestInPeaceException.class, () -> api.listOrdersByStrategyAlwaysContinues(null));
	}

	@Test
	void strategyStream_lazilyFlattensEveryPage() {
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("[{\"id\":\"1\"},{\"id\":\"2\"},{\"id\":\"3\"}]"));
		PaginationStrategy<Order> strategy = ctx -> Optional.empty();

		long count = api.streamOrdersByStrategy(strategy).count();
		assertEquals(3, count);
	}

	@Test
	void strategyIterator_flattensEveryPage() {
		server.on(HTTPMethod.GET, "/orders", MockResponse.ok("[{\"id\":\"1\"}]"));
		PaginationStrategy<Order> strategy = ctx -> Optional.empty();

		Iterator<Order> iterator = api.iterateOrdersByStrategy(strategy);
		assertTrue(iterator.hasNext());
		assertEquals("1", iterator.next().id);
		assertFalse(iterator.hasNext());
	}

	private static Map<String, String> queryParams(String name, String value) {
		Map<String, String> params = new HashMap<>();
		params.put(name, value);
		return Collections.unmodifiableMap(params);
	}

}
