package com.shri.restinpeace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Response caching, including {@code @NoCache} - split out of
 * {@code RipIntegrationTest} (see {@link AbstractRipIntegrationTest}).
 */
class RipCachingIntegrationTest extends AbstractRipIntegrationTest {

	@Test
	void responseCache_freshEntry_isServedWithoutHittingTheNetworkAgain() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String first = api.getCacheable(port, "42");
		String second = api.getCacheable(port, "42");

		assertEquals("cached-value", first);
		assertEquals("cached-value", second);
		assertEquals(1, CACHEABLE_HITS.get());
	}

	@Test
	void responseCache_noCacheAnnotation_alwaysHitsNetwork() {
		LocalApi api = RIP.getClient(LocalApi.class);

		api.getCacheableNoCache(port, "42");
		api.getCacheableNoCache(port, "42");

		assertEquals(2, CACHEABLE_HITS.get());
	}

	@Test
	void responseCache_globalCacheKeyIncludesQueryStringDisabled_conflatesDifferentQueryStrings() {
		RIP.setCacheKeyIncludesQueryString(false);
		try {
			LocalApi api = RIP.getClient(LocalApi.class);

			String first = api.getCacheableWithQuery(port, "42", "v1");
			String second = api.getCacheableWithQuery(port, "42", "v2");

			assertEquals("cached-value", first);
			// Same entry as the v1 call's - the disabled global default collapses
			// every query string variant of this path onto one cache key, so this
			// is a cache hit rather than a second real request.
			assertEquals("cached-value", second);
			assertEquals(1, CACHEABLE_HITS.get());
		} finally {
			RIP.setCacheKeyIncludesQueryString(true); // restore the default for every other test
		}
	}

}
