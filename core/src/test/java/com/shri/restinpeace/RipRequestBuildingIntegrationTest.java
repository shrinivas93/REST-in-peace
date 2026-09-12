package com.shri.restinpeace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.exception.RestInPeaceException;

/**
 * Basic HTTP verb dispatch, path/query/header param application (including
 * {@code @QueryMap}/{@code @HeaderMap}/{@code @Headers}), and required-value
 * validation - the part of {@code RipIntegrationTest} exercising request
 * building, split out on its own (see {@link AbstractRipIntegrationTest}).
 */
class RipRequestBuildingIntegrationTest extends AbstractRipIntegrationTest {

	@Test
	void get_withPathQueryAndHeader_sendsCorrectRequest() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.get(port, "abc", 7, "custom-value");

		assertEquals("ok", result);
		CapturedRequest request = LAST_REQUEST.get();
		assertEquals("GET", request.method);
		assertEquals("/items/abc", request.path);
		assertEquals("q=7", request.query);
		assertEquals("custom-value", request.header("X-Custom"));
	}

	@Test
	void get_withPathParamContainingSpace_encodesAndDeliversLiteralSpace() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.get(port, "a b", 7, "custom-value");

		assertEquals("ok", result);
		assertEquals("/items/a b", LAST_REQUEST.get().path);
	}

	@Test
	void get_withPathParamContainingQuestionMark_encodesInsteadOfStartingQueryString() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.get(port, "a?b=c", 7, "custom-value");

		assertEquals("ok", result);
		CapturedRequest request = LAST_REQUEST.get();
		assertEquals("/items/a?b=c", request.path);
		assertEquals("q=7", request.query);
	}

	@Test
	void get_withPathParamContainingSlash_encodesAsSingleSegment() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.get(port, "a/b", 7, "custom-value");

		assertEquals("ok", result);
		assertEquals("/items/a/b", LAST_REQUEST.get().path);
	}

	@Test
	void post_withStringBody_sendsBodyRaw() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.post(port, "xyz", "raw-body-content");

		assertEquals("ok", result);
		CapturedRequest request = LAST_REQUEST.get();
		assertEquals("POST", request.method);
		assertEquals("/items/xyz", request.path);
		assertEquals("raw-body-content", request.body);
		assertFalse(request.header("Content-Type").startsWith("application/json"));
	}

	@Test
	void put_withObjectBody_sendsJsonSerializedBody() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.put(port, "obj", new Payload("Shrinivas", 1993));

		assertEquals("ok", result);
		CapturedRequest request = LAST_REQUEST.get();
		assertEquals("PUT", request.method);
		assertTrue(request.body.contains("\"name\":\"Shrinivas\""));
		assertTrue(request.body.contains("\"age\":1993"));
		assertTrue(request.header("Content-Type").startsWith("application/json"));
	}

	@Test
	void patch_withBody_sendsRequest() {
		LocalApi api = RIP.getClient(LocalApi.class);

		api.patch(port, "p", "patch-body");

		assertEquals("PATCH", LAST_REQUEST.get().method);
		assertEquals("patch-body", LAST_REQUEST.get().body);
	}

	@Test
	void delete_withBody_sendsRequest() {
		LocalApi api = RIP.getClient(LocalApi.class);

		api.delete(port, "d", "delete-body");

		assertEquals("DELETE", LAST_REQUEST.get().method);
		assertEquals("delete-body", LAST_REQUEST.get().body);
	}

	@Test
	void head_sendsRequestAndReturnsEmptyBody() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.head(port, "h");

		assertEquals("HEAD", LAST_REQUEST.get().method);
		assertTrue(result == null || result.isEmpty());
	}

	@Test
	void options_sendsRequestAndReturnsBody() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.options(port, "o");

		assertEquals("OPTIONS", LAST_REQUEST.get().method);
		assertEquals("ok", result);
	}

	@Test
	void optionalQueryParam_missingArg_usesDefaultValue() {
		LocalApi api = RIP.getClient(LocalApi.class);

		api.getWithOptionalQuery(port, "d", null);

		assertEquals("q=42", LAST_REQUEST.get().query);
	}

	@Test
	void queryMap_withEntries_sendsEachAsQueryParam() {
		LocalApi api = RIP.getClient(LocalApi.class);
		Map<String, String> filters = new LinkedHashMap<>();
		filters.put("status", "active");
		filters.put("sort", "name");

		api.getWithQueryMap(port, "abc", filters);

		Set<String> queryParams = new HashSet<>(Arrays.asList(LAST_REQUEST.get().query.split("&")));
		assertEquals(new HashSet<>(Arrays.asList("status=active", "sort=name")), queryParams);
	}

	@Test
	void queryParam_withListValue_repeatsParamOncePerElement() {
		LocalApi api = RIP.getClient(LocalApi.class);

		api.getWithMultiValueQuery(port, "abc", Arrays.asList("a", "b", "c"));

		assertEquals("tag=a&tag=b&tag=c", LAST_REQUEST.get().query);
	}

	@Test
	void queryMap_withListValue_repeatsThatEntryOncePerElement() {
		LocalApi api = RIP.getClient(LocalApi.class);
		Map<String, Object> filters = new LinkedHashMap<>();
		filters.put("status", "active");
		filters.put("tag", Arrays.asList("a", "b"));

		api.getWithMultiValueQueryMap(port, "abc", filters);

		Set<String> queryParams = new HashSet<>(Arrays.asList(LAST_REQUEST.get().query.split("&")));
		assertEquals(new HashSet<>(Arrays.asList("status=active", "tag=a", "tag=b")), queryParams);
	}

	@Test
	void queryMap_withNullMap_sendsNoQueryParams() {
		LocalApi api = RIP.getClient(LocalApi.class);

		api.getWithQueryMap(port, "abc", null);

		assertNull(LAST_REQUEST.get().query);
	}

	@Test
	void queryMap_withNullValue_skipsThatEntry() {
		LocalApi api = RIP.getClient(LocalApi.class);
		Map<String, String> filters = new LinkedHashMap<>();
		filters.put("status", "active");
		filters.put("skip", null);

		api.getWithQueryMap(port, "abc", filters);

		assertEquals("status=active", LAST_REQUEST.get().query);
	}

	@Test
	void queryMap_combinedWithFixedQueryParam_sendsBoth() {
		LocalApi api = RIP.getClient(LocalApi.class);
		Map<String, String> extra = new LinkedHashMap<>();
		extra.put("status", "active");

		api.getWithFixedQueryParamAndQueryMap(port, "abc", "fixedValue", extra);

		Set<String> queryParams = new HashSet<>(Arrays.asList(LAST_REQUEST.get().query.split("&")));
		assertEquals(new HashSet<>(Arrays.asList("fixed=fixedValue", "status=active")), queryParams);
	}

	@Test
	void headerMap_withEntries_sendsEachAsHeader() {
		LocalApi api = RIP.getClient(LocalApi.class);
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("X-Tenant", "acme");
		headers.put("X-Trace-Id", "trace-123");

		api.getWithHeaderMap(port, "abc", headers);

		assertEquals("acme", LAST_REQUEST.get().header("X-Tenant"));
		assertEquals("trace-123", LAST_REQUEST.get().header("X-Trace-Id"));
	}

	@Test
	void headerMap_withNullMap_sendsNoExtraHeaders() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.getWithHeaderMap(port, "abc", null);

		assertEquals("ok", result);
	}

	@Test
	void headerMap_withNullValue_skipsThatEntry() {
		LocalApi api = RIP.getClient(LocalApi.class);
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("X-Tenant", "acme");
		headers.put("X-Skip", null);

		api.getWithHeaderMap(port, "abc", headers);

		assertEquals("acme", LAST_REQUEST.get().header("X-Tenant"));
		assertNull(LAST_REQUEST.get().header("X-Skip"));
	}

	@Test
	void headers_fixedEntries_sendsEachAsHeaderWithTrimmedNameAndValue() {
		LocalApi api = RIP.getClient(LocalApi.class);

		api.getWithFixedHeaders(port, "abc");

		assertEquals("no-cache", LAST_REQUEST.get().header("Cache-Control"));
		assertEquals("2", LAST_REQUEST.get().header("X-Api-Version"));
	}

	@Test
	void headers_withOverridingHeaderParam_headerParamValueWins() {
		LocalApi api = RIP.getClient(LocalApi.class);

		api.getWithFixedHeaderAndOverridingHeaderParam(port, "abc", "from-param");

		assertEquals("from-param", LAST_REQUEST.get().header("X-Custom"));
	}

	@Test
	void requiredQueryParam_missingArg_throwsRestInPeaceException() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RestInPeaceException exception = assertThrows(RestInPeaceException.class,
				() -> api.getWithMissingRequiredQuery(port, "d", null));
		assertTrue(exception.getMessage().contains("Missing required value"));
	}

	@Test
	void requiredHeaderParam_missingArg_throwsRestInPeaceException() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RestInPeaceException exception = assertThrows(RestInPeaceException.class,
				() -> api.getWithMissingRequiredHeader(port, "d", null));
		assertTrue(exception.getMessage().contains("Missing required value"));
	}

	@Test
	void pathParam_nullArg_throwsRestInPeaceException() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RestInPeaceException exception = assertThrows(RestInPeaceException.class,
				() -> api.getWithNullPathParam(port, null));
		assertTrue(exception.getMessage().contains("Missing value for path param"));
	}

}
