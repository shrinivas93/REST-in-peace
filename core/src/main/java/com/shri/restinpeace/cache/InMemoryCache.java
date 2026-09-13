package com.shri.restinpeace.cache;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The default {@link Cache} implementation: an unbounded, process-local
 * {@code ConcurrentHashMap} - fine for most single-process uses, but not
 * shared across JVMs. By default an entry is never removed except via
 * {@link #evict}/{@link #clear} or the process exiting, same as always;
 * {@link #InMemoryCache(long)} opts into an additional, automatic
 * time-based eviction on top of that, for a process that runs long enough
 * for entries covering many distinct, never-repeated URLs (many different
 * {@code /items/{id}}s that come and go) to otherwise accumulate forever.
 * Provide a custom {@link Cache} instead for bounded/LRU eviction by entry
 * count or a shared external store.
 */
public final class InMemoryCache implements Cache {

	private final ConcurrentHashMap<String, CachedResponse> entries = new ConcurrentHashMap<>();
	private final long maxEntryAgeMillis;

	/**
	 * Creates a cache with no time-based eviction at all - an entry lives
	 * until explicitly {@link #evict}ed/{@link #clear}ed, no matter how
	 * long ago it was stored. Identical to this class's original behavior.
	 */
	public InMemoryCache() {
		this.maxEntryAgeMillis = -1;
	}

	/**
	 * Creates a cache that also automatically forgets an entry once it's
	 * been held for longer than {@code maxEntryAgeMillis}, checked lazily
	 * on the next {@link #get} for that same key (there's no background
	 * thread sweeping every entry proactively, so a key that's stored once
	 * and never looked up again still isn't reclaimed until this cache
	 * itself is discarded - the same "forever, unless something touches it
	 * again" limitation the unbounded default already has, just with a
	 * smaller blast radius).
	 *
	 * <p>
	 * This is a separate clock from server-driven freshness
	 * ({@link CachedResponse#getFreshUntilEpochMillis()}/{@link CachedResponse#isFresh()}):
	 * an entry stored only for {@code ETag}/{@code Last-Modified}
	 * revalidation (e.g. {@code Cache-Control: no-cache}) is stale from the
	 * moment it's stored, by design, and must keep being usable for that
	 * revalidation round trip - this age limit is purely "how long RIP has
	 * been holding onto this at all," measured from
	 * {@link CachedResponse#getStoredAtEpochMillis()} (reset on every
	 * successful revalidation, not just the original store), independent
	 * of whether the entry is currently fresh or stale-but-revalidatable.
	 *
	 * @param maxEntryAgeMillis how long an entry may be held before it's
	 *                          treated as gone; must be positive
	 */
	public InMemoryCache(long maxEntryAgeMillis) {
		if (maxEntryAgeMillis <= 0) {
			throw new IllegalArgumentException("maxEntryAgeMillis must be positive.");
		}
		this.maxEntryAgeMillis = maxEntryAgeMillis;
	}

	@Override
	public CachedResponse get(String key) {
		CachedResponse cached = entries.get(key);
		if (cached != null && isTooOld(cached)) {
			entries.remove(key, cached);
			return null;
		}
		return cached;
	}

	@Override
	public void put(String key, CachedResponse response) {
		entries.put(key, response);
	}

	@Override
	public void evict(String key) {
		entries.remove(key);
	}

	@Override
	public void clear() {
		entries.clear();
	}

	private boolean isTooOld(CachedResponse cached) {
		return maxEntryAgeMillis > 0
				&& System.currentTimeMillis() - cached.getStoredAtEpochMillis() > maxEntryAgeMillis;
	}

}
