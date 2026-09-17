package com.shri.restinpeace.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import kong.unirest.JsonObjectMapper;

import com.shri.restinpeace.RIP;
import com.shri.restinpeace.RipClientConfig;
import com.shri.restinpeace.cache.InMemoryCache;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.exception.RestInPeaceHttpException;

/**
 * Exercises response caching (see {@code com.shri.restinpeace.cache}) end to
 * end against a real {@link MockRestServer} - using {@code MockRestServer}'s
 * predicate-matcher {@code on(...)} overload to script a server that only
 * answers {@code 304 Not Modified} once it actually receives the
 * {@code If-None-Match} RIP's cache is expected to send, exactly the way a
 * real conditional-GET-aware server would.
 */
class ResponseCachingTest {

	private MockRestServer server;
	private InMemoryCache cache;
	private CacheTestApi api;

	@BeforeEach
	void setUp() {
		RIP.setObjectMapper(new JsonObjectMapper());
		server = MockRestServer.start();
		cache = new InMemoryCache();
		api = RIP.getClient(CacheTestApi.class, RipClientConfig.builder().baseUrl(server.baseUrl()).cache(cache).build());
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	@Test
	void freshEntry_isServedWithoutHittingTheNetworkAgain() {
		server.on(HTTPMethod.GET, "/items/{id}", MockResponse.ok("{\"v\":1}").header("Cache-Control", "max-age=60"));

		String first = api.getItem("42");
		String second = api.getItem("42");

		assertEquals("{\"v\":1}", first);
		assertEquals("{\"v\":1}", second);
		assertEquals(1, server.requestCount());
	}

	@Test
	void staleEntryWithETag_revalidatesAndServesTheCachedBodyOn304() {
		server.on(HTTPMethod.GET, "/items/{id}", request -> request.getHeader("If-None-Match") == null,
				MockResponse.ok("{\"v\":1}").header("Cache-Control", "max-age=0").header("ETag", "\"abc\""));
		server.on(HTTPMethod.GET, "/items/{id}", request -> "\"abc\"".equals(request.getHeader("If-None-Match")),
				MockResponse.notModified());

		String first = api.getItem("42");
		String second = api.getItem("42");

		assertEquals("{\"v\":1}", first);
		assertEquals("{\"v\":1}", second);
		assertEquals(2, server.requestCount());
	}

	@Test
	void asyncCall_staleEntryWithETag_revalidatesAndServesTheCachedBodyOn304()
			throws InterruptedException, ExecutionException, TimeoutException {
		server.on(HTTPMethod.GET, "/items/{id}", request -> request.getHeader("If-None-Match") == null,
				MockResponse.ok("{\"v\":1}").header("Cache-Control", "max-age=0").header("ETag", "\"abc\""));
		server.on(HTTPMethod.GET, "/items/{id}", request -> "\"abc\"".equals(request.getHeader("If-None-Match")),
				MockResponse.notModified());

		String first = api.getItemAsync("42").get(2, TimeUnit.SECONDS);
		String second = api.getItemAsync("42").get(2, TimeUnit.SECONDS);

		assertEquals("{\"v\":1}", first);
		assertEquals("{\"v\":1}", second);
		assertEquals(2, server.requestCount());
	}

	@Test
	void staleEntryWithLastModifiedOnly_revalidatesUsingIfModifiedSince() {
		server.on(HTTPMethod.GET, "/items/{id}", request -> request.getHeader("If-Modified-Since") == null,
				MockResponse.ok("{\"v\":1}").header("Cache-Control", "max-age=0").header("Last-Modified",
						"Wed, 21 Oct 2015 07:28:00 GMT"));
		server.on(HTTPMethod.GET, "/items/{id}",
				request -> "Wed, 21 Oct 2015 07:28:00 GMT".equals(request.getHeader("If-Modified-Since")),
				MockResponse.notModified());

		String first = api.getItem("42");
		String second = api.getItem("42");

		assertEquals("{\"v\":1}", first);
		assertEquals("{\"v\":1}", second);
		assertEquals(2, server.requestCount());
	}

	@Test
	void noCacheDirective_alwaysRevalidatesEvenThoughTheEntryWouldOtherwiseBeFresh() {
		server.on(HTTPMethod.GET, "/items/{id}", request -> request.getHeader("If-None-Match") == null,
				MockResponse.ok("{\"v\":1}").header("Cache-Control", "no-cache, max-age=60").header("ETag", "\"abc\""));
		server.on(HTTPMethod.GET, "/items/{id}", request -> "\"abc\"".equals(request.getHeader("If-None-Match")),
				MockResponse.notModified());

		String first = api.getItem("42");
		String second = api.getItem("42");

		assertEquals("{\"v\":1}", first);
		assertEquals("{\"v\":1}", second);
		// "no-cache" (unlike "no-store") still stores the entry, but treats it as
		// always-stale - unlike a plain max-age=60 entry (freshEntry_isServedWithoutHittingTheNetworkAgain),
		// the second call must revalidate over the network (and gets a 304).
		assertEquals(2, server.requestCount());
	}

	@Test
	void malformedMaxAge_failsOpenAndTreatsTheDirectiveAsAbsent() {
		server.on(HTTPMethod.GET, "/items/{id}", MockResponse.ok("{}").header("Cache-Control", "max-age=notanumber"));

		api.getItem("42");
		api.getItem("42");

		// A malformed max-age (with no ETag/Last-Modified either) fails open exactly
		// like no freshness/validator directive at all (see noFreshnessOrValidator_isNeverCached)
		// - never cached, not "cached forever".
		assertEquals(2, server.requestCount());
	}

	@Test
	void globalNegativeCacheTtlDefault_appliesWhenNoPerClientOverrideIsConfigured() throws Exception {
		server.on(HTTPMethod.GET, "/items/{id}", MockResponse.status(404, "{\"error\":\"not found\"}"));
		RIP.setNegativeCacheTtlMillis(60_000);
		try {
			assertThrows(RestInPeaceHttpException.class, () -> api.getItem("42"));
			assertThrows(RestInPeaceHttpException.class, () -> api.getItem("42"));

			assertEquals(1, server.requestCount());
		} finally {
			// RIP.setNegativeCacheTtlMillis(long) has no symmetric "off" call (unlike
			// RIP.setCache(null)) since the shared default is a process-global static -
			// reset it directly so this test doesn't leak negative caching into every
			// other test in this class (several of which rely on it being off by default).
			Class<?> cacheCoordinatorClass = Class.forName("com.shri.restinpeace.internal.CacheCoordinator");
			java.lang.reflect.Field defaultTtlField = cacheCoordinatorClass
					.getDeclaredField("DEFAULT_NEGATIVE_CACHE_TTL_MILLIS");
			defaultTtlField.setAccessible(true);
			defaultTtlField.set(null, null);
		}
	}

	@Test
	void noStoreDirective_isNeverCached() {
		server.on(HTTPMethod.GET, "/items/{id}", MockResponse.ok("{}").header("Cache-Control", "no-store"));

		api.getItem("42");
		api.getItem("42");

		assertEquals(2, server.requestCount());
	}

	@Test
	void noFreshnessOrValidator_isNeverCached() {
		server.on(HTTPMethod.GET, "/items/{id}", MockResponse.ok("{}"));

		api.getItem("42");
		api.getItem("42");

		assertEquals(2, server.requestCount());
	}

	@Test
	void noCacheAnnotation_alwaysHitsNetworkEvenWithMaxAge() {
		server.on(HTTPMethod.GET, "/items/{id}", MockResponse.ok("{}").header("Cache-Control", "max-age=60"));

		api.getItemNoCache("42");
		api.getItemNoCache("42");

		assertEquals(2, server.requestCount());
	}

	@Test
	void twoClientsWithSeparateCaches_dontShareEntries() {
		server.on(HTTPMethod.GET, "/items/{id}", MockResponse.ok("{}").header("Cache-Control", "max-age=60"));
		CacheTestApi secondApi = RIP.getClient(CacheTestApi.class,
				RipClientConfig.builder().baseUrl(server.baseUrl()).cache(new InMemoryCache()).build());

		api.getItem("42");
		secondApi.getItem("42");

		assertEquals(2, server.requestCount());
	}

	@Test
	void noCacheConfigured_behavesExactlyAsBefore() {
		CacheTestApi uncachedApi = RIP.getClient(CacheTestApi.class, server.baseUrl());
		server.on(HTTPMethod.GET, "/items/{id}", MockResponse.ok("{}").header("Cache-Control", "max-age=60"));

		uncachedApi.getItem("42");
		uncachedApi.getItem("42");

		assertEquals(2, server.requestCount());
	}

	@Test
	void vary_sameHeaderValue_isServedFromCache() {
		server.on(HTTPMethod.GET, "/localized/{id}",
				MockResponse.ok("{\"lang\":\"en\"}").header("Cache-Control", "max-age=60").header("Vary",
						"Accept-Language"));

		String first = api.getLocalizedItem("42", "en");
		String second = api.getLocalizedItem("42", "en");

		assertEquals("{\"lang\":\"en\"}", first);
		assertEquals("{\"lang\":\"en\"}", second);
		assertEquals(1, server.requestCount());
	}

	@Test
	void vary_differentHeaderValue_isNotServedTheOtherVariantsCache() {
		server.on(HTTPMethod.GET, "/localized/{id}", request -> "en".equals(request.getHeader("Accept-Language")),
				MockResponse.ok("{\"lang\":\"en\"}").header("Cache-Control", "max-age=60").header("Vary",
						"Accept-Language"));
		server.on(HTTPMethod.GET, "/localized/{id}", request -> "fr".equals(request.getHeader("Accept-Language")),
				MockResponse.ok("{\"lang\":\"fr\"}").header("Cache-Control", "max-age=60").header("Vary",
						"Accept-Language"));

		String english = api.getLocalizedItem("42", "en");
		String french = api.getLocalizedItem("42", "fr");

		assertEquals("{\"lang\":\"en\"}", english);
		assertEquals("{\"lang\":\"fr\"}", french);
		assertEquals(2, server.requestCount());
	}

	@Test
	void asyncCall_vary_differentHeaderValue_isNotServedTheOtherVariantsCache()
			throws InterruptedException, ExecutionException, TimeoutException {
		server.on(HTTPMethod.GET, "/localized/{id}", request -> "en".equals(request.getHeader("Accept-Language")),
				MockResponse.ok("{\"lang\":\"en\"}").header("Cache-Control", "max-age=60").header("Vary",
						"Accept-Language"));
		server.on(HTTPMethod.GET, "/localized/{id}", request -> "fr".equals(request.getHeader("Accept-Language")),
				MockResponse.ok("{\"lang\":\"fr\"}").header("Cache-Control", "max-age=60").header("Vary",
						"Accept-Language"));

		String english = api.getLocalizedItemAsync("42", "en").get(2, TimeUnit.SECONDS);
		String french = api.getLocalizedItemAsync("42", "fr").get(2, TimeUnit.SECONDS);

		assertEquals("{\"lang\":\"en\"}", english);
		assertEquals("{\"lang\":\"fr\"}", french);
		assertEquals(2, server.requestCount());
	}

	@Test
	void vary_wildcard_isNeverCached() {
		server.on(HTTPMethod.GET, "/localized/{id}",
				MockResponse.ok("{}").header("Cache-Control", "max-age=60").header("Vary", "*"));

		api.getLocalizedItem("42", "en");
		api.getLocalizedItem("42", "en");

		assertEquals(2, server.requestCount());
	}

	@Test
	void vary_aDifferentVariantMiss_doesNotEvictAnExistingValidVariant() {
		server.on(HTTPMethod.GET, "/localized/{id}", request -> "en".equals(request.getHeader("Accept-Language")),
				MockResponse.ok("{\"lang\":\"en\"}").header("Cache-Control", "max-age=60").header("Vary",
						"Accept-Language"));
		server.on(HTTPMethod.GET, "/localized/{id}", request -> "fr".equals(request.getHeader("Accept-Language")),
				MockResponse.ok("{\"lang\":\"fr\"}")); // no Cache-Control/ETag at all - never storable

		api.getLocalizedItem("42", "en"); // caches the "en" variant
		api.getLocalizedItem("42", "fr"); // different variant, unstorable response - must not evict "en"
		String english = api.getLocalizedItem("42", "en"); // still cached - no extra network call

		assertEquals("{\"lang\":\"en\"}", english);
		assertEquals(2, server.requestCount()); // 1 for "en", 1 for "fr" - the second "en" call was a cache hit
	}

	@Test
	void asyncCall_alsoServesAFreshEntryWithoutHittingTheNetworkAgain()
			throws InterruptedException, ExecutionException, TimeoutException {
		server.on(HTTPMethod.GET, "/items/{id}", MockResponse.ok("{\"v\":1}").header("Cache-Control", "max-age=60"));

		String first = api.getItemAsync("42").get(2, TimeUnit.SECONDS);
		String second = api.getItemAsync("42").get(2, TimeUnit.SECONDS);

		assertEquals("{\"v\":1}", first);
		assertEquals("{\"v\":1}", second);
		assertEquals(1, server.requestCount());
	}

	@Test
	void differentQueryStrings_onTheSamePath_areNeverConflated() {
		server.on(HTTPMethod.GET, "/search", MockResponse.ok("{\"page\":1}").header("Cache-Control", "max-age=60"));

		String page1First = api.search("1");
		String page2 = api.search("2");
		String page1Second = api.search("1");

		assertEquals("{\"page\":1}", page1First);
		assertEquals("{\"page\":1}", page2);
		assertEquals("{\"page\":1}", page1Second);
		// 1 real request for page=1, 1 for page=2 (a different cache key), and the
		// second page=1 call is a cache hit - 2 total, not 1 (which is what a cache
		// key blind to the query string would produce, wrongly serving page=2's
		// call - or any call at all after the first - out of page=1's own entry).
		assertEquals(2, server.requestCount());
	}

	@Test
	void cacheKeyIncludesQueryStringDisabled_conflatesDifferentQueryStringsOnTheSamePath() {
		CacheTestApi noQueryStringKeyApi = RIP.getClient(CacheTestApi.class, RipClientConfig.builder()
				.baseUrl(server.baseUrl()).cache(cache).cacheKeyIncludesQueryString(false).build());
		server.on(HTTPMethod.GET, "/search", MockResponse.ok("{\"page\":1}").header("Cache-Control", "max-age=60"));

		String page1 = noQueryStringKeyApi.search("1");
		String page2 = noQueryStringKeyApi.search("2");

		assertEquals("{\"page\":1}", page1);
		// Same entry as page1's - the opt-out deliberately collapses every query
		// string variant of /search onto one cache key, so this is a cache hit
		// (still page 1's stored body) rather than a second real request.
		assertEquals("{\"page\":1}", page2);
		assertEquals(1, server.requestCount());
	}

	@Test
	void manualEviction_viaThePubliclyComputableKey_forcesTheNextCallBackToTheNetwork() {
		server.on(HTTPMethod.GET, "/items/{id}", MockResponse.ok("{\"v\":1}").header("Cache-Control", "max-age=60"));

		api.getItem("42");
		cache.evict(com.shri.restinpeace.cache.Cache.key(HTTPMethod.GET, server.baseUrl() + "/items/42"));
		api.getItem("42");

		assertEquals(2, server.requestCount());
	}

	@Test
	void staleWhileRevalidate_servesTheStaleBodyImmediatelyThenRefreshesInTheBackground() throws InterruptedException {
		server.on(HTTPMethod.GET, "/items/{id}",
				MockResponse.ok("{\"v\":1}").header("Cache-Control", "max-age=0, stale-while-revalidate=60"));
		String first = api.getItem("42"); // stores v1 - immediately stale, but now within its swr window

		server.on(HTTPMethod.GET, "/items/{id}",
				MockResponse.ok("{\"v\":2}").header("Cache-Control", "max-age=0, stale-while-revalidate=60"));
		String second = api.getItem("42"); // still v1 - served from the stale entry, not blocked on a re-fetch

		assertEquals("{\"v\":1}", first);
		assertEquals("{\"v\":1}", second);
		awaitCachedBody("/items/42", "{\"v\":2}"); // the background revalidation triggered by the second call

		String third = api.getItem("42"); // the background refresh already replaced the entry with v2
		assertEquals("{\"v\":2}", third);
	}

	@Test
	void staleWhileRevalidate_pastItsOwnWindow_fallsBackToASynchronousRefetch() throws InterruptedException {
		server.on(HTTPMethod.GET, "/items/{id}",
				MockResponse.ok("{\"v\":1}").header("Cache-Control", "max-age=0, stale-while-revalidate=1"));
		api.getItem("42");

		Thread.sleep(1100); // past both max-age=0 and the 1-second stale-while-revalidate window

		server.on(HTTPMethod.GET, "/items/{id}",
				MockResponse.ok("{\"v\":2}").header("Cache-Control", "max-age=0, stale-while-revalidate=1"));
		String second = api.getItem("42");

		assertEquals("{\"v\":2}", second); // synchronous re-fetch, not the stale v1 body
		assertEquals(2, server.requestCount());
	}

	@Test
	void staleWhileRevalidate_asyncCall_alsoServesTheStaleBodyImmediately()
			throws InterruptedException, ExecutionException, TimeoutException {
		server.on(HTTPMethod.GET, "/items/{id}",
				MockResponse.ok("{\"v\":1}").header("Cache-Control", "max-age=0, stale-while-revalidate=60"));
		api.getItemAsync("42").get(2, TimeUnit.SECONDS);

		server.on(HTTPMethod.GET, "/items/{id}",
				MockResponse.ok("{\"v\":2}").header("Cache-Control", "max-age=0, stale-while-revalidate=60"));
		String second = api.getItemAsync("42").get(2, TimeUnit.SECONDS);

		assertEquals("{\"v\":1}", second);
		awaitCachedBody("/items/42", "{\"v\":2}");

		String third = api.getItemAsync("42").get(2, TimeUnit.SECONDS);
		assertEquals("{\"v\":2}", third);
	}

	/**
	 * Polls the same {@link InMemoryCache} instance {@code api} is configured
	 * with directly, rather than {@code server.requestCount()} - the
	 * background revalidation's real network round trip completing (which is
	 * what bumps the request count) and its {@code cache.put(...)} happen on
	 * the same background thread but aren't the same instant, so polling the
	 * cache itself is what actually avoids the race.
	 */
	private void awaitCachedBody(String path, String expectedBody) throws InterruptedException {
		String key = com.shri.restinpeace.cache.Cache.key(HTTPMethod.GET, server.baseUrl() + path);
		long deadline = System.currentTimeMillis() + 2000;
		while (System.currentTimeMillis() < deadline) {
			com.shri.restinpeace.cache.CachedResponse cached = cache.get(key);
			if (cached != null && expectedBody.equals(cached.getBody())) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("Cache never observed body " + expectedBody + " for key " + key);
	}

	@Test
	void negativeCaching_confirmedNotFound_isServedFromCacheWithoutHittingTheNetworkAgain() {
		CacheTestApi negativeCachingApi = RIP.getClient(CacheTestApi.class, RipClientConfig.builder()
				.baseUrl(server.baseUrl()).cache(cache).negativeCacheTtlMillis(60_000).build());
		server.on(HTTPMethod.GET, "/items/{id}", MockResponse.status(404, "{\"error\":\"not found\"}"));

		RestInPeaceHttpException first = assertThrows(RestInPeaceHttpException.class,
				() -> negativeCachingApi.getItem("42"));
		RestInPeaceHttpException second = assertThrows(RestInPeaceHttpException.class,
				() -> negativeCachingApi.getItem("42"));

		assertEquals(404, first.getStatus());
		assertEquals(404, second.getStatus());
		assertEquals(1, server.requestCount());
	}

	@Test
	void withoutNegativeCachingConfigured_confirmedNotFound_isNeverCached() {
		server.on(HTTPMethod.GET, "/items/{id}", MockResponse.status(404, "{\"error\":\"not found\"}"));

		assertThrows(RestInPeaceHttpException.class, () -> api.getItem("42"));
		assertThrows(RestInPeaceHttpException.class, () -> api.getItem("42"));

		assertEquals(2, server.requestCount());
	}

	@Test
	void negativeCaching_isSkippedByNoCacheTheSameWayAsOrdinaryCaching() {
		CacheTestApi negativeCachingApi = RIP.getClient(CacheTestApi.class, RipClientConfig.builder()
				.baseUrl(server.baseUrl()).cache(cache).negativeCacheTtlMillis(60_000).build());
		server.on(HTTPMethod.GET, "/items/{id}", MockResponse.status(404, "{}"));

		assertThrows(RestInPeaceHttpException.class, () -> negativeCachingApi.getItemNoCache("42"));
		assertThrows(RestInPeaceHttpException.class, () -> negativeCachingApi.getItemNoCache("42"));

		assertEquals(2, server.requestCount());
	}

}
