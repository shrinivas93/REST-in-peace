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

}
