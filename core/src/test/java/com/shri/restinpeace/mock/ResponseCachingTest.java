package com.shri.restinpeace.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

}
