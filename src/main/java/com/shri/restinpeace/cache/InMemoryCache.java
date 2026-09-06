package com.shri.restinpeace.cache;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The default {@link Cache} implementation: an unbounded, process-local
 * {@code ConcurrentHashMap} - fine for most single-process uses, but not
 * shared across JVMs and never evicted except via {@link #evict}/
 * {@link #clear} or the process exiting. Provide a custom {@link Cache}
 * instead for bounded/LRU eviction or a shared external store.
 */
public final class InMemoryCache implements Cache {

	private final ConcurrentHashMap<String, CachedResponse> entries = new ConcurrentHashMap<>();

	@Override
	public CachedResponse get(String key) {
		return entries.get(key);
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

}
