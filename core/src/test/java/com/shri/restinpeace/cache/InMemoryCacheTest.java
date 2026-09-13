package com.shri.restinpeace.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.constant.HTTPMethod;

/**
 * {@link InMemoryCache}'s own behavior in isolation - the unbounded default,
 * opt-in max-entry-age eviction, and manual {@link Cache#evict}/{@link Cache#clear} -
 * plus {@link Cache#key} directly. End-to-end caching behavior (freshness,
 * revalidation, {@code Vary}) is covered by {@code ResponseCachingTest}
 * against a real {@code MockRestServer}; this class is only about the store
 * itself.
 */
class InMemoryCacheTest {

	private static CachedResponse entry() {
		return new CachedResponse(200, Collections.emptyMap(), "body", System.currentTimeMillis() + 60_000);
	}

	@Test
	void defaultConstructor_neverEvictsByAge() {
		InMemoryCache cache = new InMemoryCache();
		cache.put("key", entry());

		assertNotNull(cache.get("key"), "the unbounded default must never age an entry out on its own");
	}

	@Test
	void maxEntryAge_constructor_rejectsNonPositiveValues() {
		assertThrows(IllegalArgumentException.class, () -> new InMemoryCache(0));
		assertThrows(IllegalArgumentException.class, () -> new InMemoryCache(-1));
	}

	@Test
	void withMaxEntryAge_stillReturnsAnEntryYoungerThanTheLimit() {
		InMemoryCache cache = new InMemoryCache(60_000);
		cache.put("key", entry());

		assertNotNull(cache.get("key"));
	}

	@Test
	void withMaxEntryAge_removesAnEntryOlderThanTheLimitOnNextGet() throws InterruptedException {
		InMemoryCache cache = new InMemoryCache(1);
		cache.put("key", entry());
		Thread.sleep(20);

		assertNull(cache.get("key"), "an entry older than maxEntryAgeMillis must be treated as gone");
		assertNull(cache.get("key"), "and must actually have been removed, not just hidden once");
	}

	@Test
	void evict_removesOnlyTheGivenKey() {
		InMemoryCache cache = new InMemoryCache();
		cache.put("a", entry());
		cache.put("b", entry());

		cache.evict("a");

		assertNull(cache.get("a"));
		assertNotNull(cache.get("b"));
	}

	@Test
	void clear_removesEveryEntry() {
		InMemoryCache cache = new InMemoryCache();
		cache.put("a", entry());
		cache.put("b", entry());

		cache.clear();

		assertNull(cache.get("a"));
		assertNull(cache.get("b"));
	}

	@Test
	void key_isMethodSpaceUrl_queryStringIncludedVerbatim() {
		assertEquals("GET https://api.example.com/items/42", Cache.key(HTTPMethod.GET, "https://api.example.com/items/42"));
		assertEquals("GET https://api.example.com/items?page=2",
				Cache.key(HTTPMethod.GET, "https://api.example.com/items?page=2"));
	}

	@Test
	void key_computedTheSameWayTwice_matchesForManualEviction() {
		InMemoryCache cache = new InMemoryCache();
		String url = "https://api.example.com/items/42";
		cache.put(Cache.key(HTTPMethod.GET, url), entry());

		cache.evict(Cache.key(HTTPMethod.GET, url));

		assertNull(cache.get(Cache.key(HTTPMethod.GET, url)));
	}

}
