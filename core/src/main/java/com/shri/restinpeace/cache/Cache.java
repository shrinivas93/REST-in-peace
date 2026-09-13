package com.shri.restinpeace.cache;

import com.shri.restinpeace.constant.HTTPMethod;

/**
 * A pluggable store for {@code GET} response caching, attached via
 * {@link com.shri.restinpeace.RipClientConfig.Builder#cache(Cache)} (one
 * client) or {@link com.shri.restinpeace.RIP#setCache(Cache)} (the shared
 * default for every client without its own). RIP honors whatever the server
 * actually says via {@code Cache-Control}/{@code ETag}/{@code Last-Modified}
 * - a {@code Cache} implementation is purely storage, not a policy decision;
 * see {@link InMemoryCache} for the default, in-memory implementation
 * (which also supports an optional, opt-in max entry age - "eviction on the
 * basis of time" as an automatic background concern, distinct from the
 * manual eviction described below).
 *
 * <p>
 * Keyed by {@link #key(HTTPMethod, String)} - {@code "<HTTP method> <request
 * URL>"} (e.g. {@code "GET https://api.example.com/items?page=2"}) - a
 * single slot per key, the newest variant replacing the previous one, rather
 * than storing every {@code Vary}-distinguished variant at once. A response
 * naming a {@code Vary} header is still never served to a request whose
 * current value for that header differs from the one snapshotted when it
 * was stored (see {@link CachedResponse#getVaryRequestHeaders()}) - this
 * affects only how many variants stay cached at once, not correctness.
 *
 * <p>
 * By default the key's URL includes the query string, so
 * {@code /items?page=1} and {@code /items?page=2} are cached separately -
 * this can be turned off per client (see
 * {@link com.shri.restinpeace.RipClientConfig.Builder#cacheKeyIncludesQueryString(boolean)})
 * or as a shared default (see
 * {@link com.shri.restinpeace.RIP#setCacheKeyIncludesQueryString(boolean)})
 * for an endpoint whose query params don't affect the response (e.g. an
 * analytics/tracking param), trading that precision for a higher hit rate -
 * every query-string variant of the same path then shares one entry.
 *
 * <p>
 * <b>Manual eviction:</b> a consumer holding a reference to the same
 * {@code Cache} instance RIP is using (since they're the one who
 * constructed and attached it) can force one specific call's cached
 * response gone at any time - {@code myCache.evict(Cache.key(HTTPMethod.GET,
 * "https://api.example.com/items/42"))} - e.g. right after some other
 * action they know invalidates it server-side, or {@link #clear()} for
 * everything at once. Deliberately manual only: RIP itself never inspects a
 * {@code POST}/{@code PUT}/{@code DELETE} call to guess which cached
 * {@code GET}s it might have invalidated - a URL-matching heuristic at
 * best, wrong in either direction (a write to a different resource that
 * happens to share a path prefix; a write that invalidates a completely
 * different endpoint's cached list view) - so that decision is left to
 * whoever actually knows their own API's real read/write relationships.
 *
 * <p>
 * Implementations must be safe for concurrent use - a shared {@code Cache}
 * (or the {@link com.shri.restinpeace.RIP#setCache(Cache)} default) can be
 * read and written from multiple calls at once.
 */
public interface Cache {

	/**
	 * Computes the cache key RIP itself uses internally for a call to
	 * {@code url} - the same formula {@code get}/{@code put}/{@code evict}
	 * are keyed by for that exact call, so this is the value to pass to
	 * {@link #evict} to manually invalidate one specific cached response.
	 *
	 * <p>
	 * By default, {@code url} must be the <em>exact</em> absolute URL as sent
	 * on the wire, query string included in the same order/encoding RIP
	 * itself would produce (e.g. from {@code @QueryParam}/{@code @QueryMap}
	 * applied in method-declaration order) - a mismatched query string
	 * computes a different key and silently won't evict anything. For a
	 * method with no query parameters at all, this is simply the interface
	 * method's resolved URL. If the relevant client has
	 * {@code cacheKeyIncludesQueryString} set to {@code false} (see the
	 * class javadoc above), pass the URL <em>without</em> its query string
	 * instead, since that's what RIP itself keyed the entry by.
	 *
	 * @param httpMethod the call's HTTP method - always {@code GET} in
	 *                    practice, since only {@code GET} responses are
	 *                    ever cached, but accepted generally so this
	 *                    formula has exactly one implementation
	 * @param url         the exact request URL RIP keyed this entry by - the
	 *                    full URL with its query string by default, or
	 *                    without one if {@code cacheKeyIncludesQueryString}
	 *                    is turned off for the relevant client
	 * @return the cache key for that call
	 */
	static String key(HTTPMethod httpMethod, String url) {
		return httpMethod + " " + url;
	}

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
