package com.shri.restinpeace.cache;

/**
 * A pluggable store for {@code GET} response caching, attached via
 * {@link com.shri.restinpeace.RipClientConfig.Builder#cache(Cache)} (one
 * client) or {@link com.shri.restinpeace.RIP#setCache(Cache)} (the shared
 * default for every client without its own). RIP honors whatever the server
 * actually says via {@code Cache-Control}/{@code ETag}/{@code Last-Modified}
 * - a {@code Cache} implementation is purely storage, not a policy decision;
 * see {@link InMemoryCache} for the default, in-memory implementation.
 *
 * <p>
 * Keyed by {@code "<HTTP method> <resolved absolute URL>"} (e.g.
 * {@code "GET https://api.example.com/items/42"}) - a response whose
 * freshness genuinely varies by request header (per a {@code Vary} response
 * header) isn't distinguished by this key, a known simplification for now.
 *
 * <p>
 * Implementations must be safe for concurrent use - a shared {@code Cache}
 * (or the {@link com.shri.restinpeace.RIP#setCache(Cache)} default) can be
 * read and written from multiple calls at once.
 */
public interface Cache {

	/**
	 * Returns the entry stored for {@code key}, whether or not it's still
	 * fresh - a stale entry may still be usable for revalidation (see
	 * {@link CachedResponse#isFresh()}).
	 *
	 * @param key the cache key
	 * @return the stored entry, or {@code null} if nothing is cached for it
	 */
	CachedResponse get(String key);

	/**
	 * Stores (or replaces) the entry for {@code key}.
	 *
	 * @param key      the cache key
	 * @param response the entry to store
	 */
	void put(String key, CachedResponse response);

	/**
	 * Removes the entry for {@code key}, if any - e.g. after a mutating call
	 * (a {@code POST}/{@code PUT}/{@code DELETE}) that's known to invalidate
	 * a previously-cached {@code GET}.
	 *
	 * @param key the cache key to remove
	 */
	void evict(String key);

	/**
	 * Removes every stored entry.
	 */
	void clear();

}
