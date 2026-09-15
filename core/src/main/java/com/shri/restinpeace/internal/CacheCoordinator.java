package com.shri.restinpeace.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.shri.restinpeace.cache.Cache;
import com.shri.restinpeace.cache.CachedResponse;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.interceptor.RequestContext;

import kong.unirest.Cookies;
import kong.unirest.HttpMethod;
import kong.unirest.HttpRequest;
import kong.unirest.HttpRequestSummary;
import kong.unirest.HttpResponse;
import kong.unirest.UnirestParsingException;

/**
 * Response caching for one {@link RequestExecutor} instance - honoring the
 * server's own {@code Cache-Control}/{@code ETag}/{@code Last-Modified}/
 * {@code Vary} headers for a {@code GET} whose client has a {@link Cache}
 * configured. Extracted out of {@code RequestExecutor} itself since caching
 * had grown into a genuinely separate concern with its own ~20-method
 * cluster (freshness/revalidation/{@code Vary} matching, plus a synthetic
 * {@code HttpResponse} for a cache hit) - not a change in behavior, purely
 * a change in which class the same logic lives on.
 */
final class CacheCoordinator {

	static final String NO_CACHE_ATTRIBUTE = "__ripNoCache";

	private static volatile Cache DEFAULT_CACHE;
	private static volatile boolean DEFAULT_CACHE_KEY_INCLUDES_QUERY_STRING = true;
	private static volatile Long DEFAULT_NEGATIVE_CACHE_TTL_MILLIS;

	private final Cache configuredCache;
	private final Boolean configuredCacheKeyIncludesQueryString;
	private final Long configuredNegativeCacheTtlMillis;

	CacheCoordinator(Cache configuredCache, Boolean configuredCacheKeyIncludesQueryString,
			Long configuredNegativeCacheTtlMillis) {
		this.configuredCache = configuredCache;
		this.configuredCacheKeyIncludesQueryString = configuredCacheKeyIncludesQueryString;
		this.configuredNegativeCacheTtlMillis = configuredNegativeCacheTtlMillis;
	}

	/**
	 * Sets the shared default cache. See
	 * {@link com.shri.restinpeace.RIP#setCache(Cache)}.
	 *
	 * @param cache the shared default cache, or {@code null} to disable it
	 */
	static void setDefaultCache(Cache cache) {
		DEFAULT_CACHE = cache;
	}

	/**
	 * Sets whether the cache key includes the request's query string, for
	 * every client not built with a {@link com.shri.restinpeace.RipClientConfig}
	 * that sets its own via
	 * {@link com.shri.restinpeace.RipClientConfig.Builder#cacheKeyIncludesQueryString(boolean)}.
	 * See {@link com.shri.restinpeace.RIP#setCacheKeyIncludesQueryString(boolean)}.
	 *
	 * @param includeQueryString whether the cache key includes the query string
	 */
	static void setDefaultCacheKeyIncludesQueryString(boolean includeQueryString) {
		DEFAULT_CACHE_KEY_INCLUDES_QUERY_STRING = includeQueryString;
	}

	/**
	 * Sets the shared default negative-cache TTL, for every client not built
	 * with a {@link com.shri.restinpeace.RipClientConfig} that sets its own
	 * via
	 * {@link com.shri.restinpeace.RipClientConfig.Builder#negativeCacheTtlMillis(long)}.
	 * See {@link com.shri.restinpeace.RIP#setNegativeCacheTtlMillis(long)}.
	 *
	 * @param ttlMillis how long a confirmed {@code 404} stays negatively
	 *                  cached, in milliseconds
	 */
	static void setDefaultNegativeCacheTtlMillis(long ttlMillis) {
		DEFAULT_NEGATIVE_CACHE_TTL_MILLIS = ttlMillis;
	}

	/**
	 * Returns the cache this instance's calls should use - its own, from a
	 * {@link com.shri.restinpeace.RipClientConfig}, if one was set, otherwise
	 * the shared default, read dynamically so a later
	 * {@link com.shri.restinpeace.RIP#setCache(Cache)} call still takes
	 * effect for an already-built client that never set its own.
	 */
	private Cache getCache() {
		return configuredCache != null ? configuredCache : DEFAULT_CACHE;
	}

	/**
	 * Returns whether this instance's calls should key their cache entries by
	 * query string - this client's own choice, if one was set, otherwise the
	 * shared default, read dynamically so a later {@link
	 * com.shri.restinpeace.RIP#setCacheKeyIncludesQueryString(boolean)} call
	 * still takes effect for an already-built client that never set its own.
	 */
	private boolean cacheKeyIncludesQueryString() {
		return configuredCacheKeyIncludesQueryString != null ? configuredCacheKeyIncludesQueryString
				: DEFAULT_CACHE_KEY_INCLUDES_QUERY_STRING;
	}

	/**
	 * Returns how long this instance's calls negatively cache a confirmed
	 * {@code 404} - this client's own choice, if one was set, otherwise the
	 * shared default, read dynamically so a later {@link
	 * com.shri.restinpeace.RIP#setNegativeCacheTtlMillis(long)} call still
	 * takes effect for an already-built client that never set its own.
	 *
	 * @return the negative-cache TTL in milliseconds, or {@code null} for no
	 *         negative caching at all
	 */
	private Long negativeCacheTtlMillis() {
		return configuredNegativeCacheTtlMillis != null ? configuredNegativeCacheTtlMillis
				: DEFAULT_NEGATIVE_CACHE_TTL_MILLIS;
	}

	/**
	 * Wraps a {@code String}-decoding network call with response caching -
	 * only ever engaged for a {@code GET} whose client has a {@link Cache}
	 * configured and isn't {@code @NoCache}, in which case {@code call}
	 * itself may never run at all (a fresh cache hit). Not applicable to a
	 * {@code byte[]}/{@code File} response - see {@link Cache}'s javadoc.
	 *
	 * @param request the request about to be sent - mutated with
	 *                {@code If-None-Match}/{@code If-Modified-Since} when a
	 *                stale, revalidatable entry exists
	 * @param context this call's context, used for its HTTP method, URL,
	 *                and {@code @NoCache} marker
	 * @param call    the real network call
	 * @return {@code call} unchanged if caching doesn't apply here,
	 *         otherwise a wrapping supplier that may serve a cached response
	 *         instead of invoking {@code call} at all
	 */
	Supplier<HttpResponse<String>> wrapWithCache(HttpRequest<?> request, RequestContext context,
			Supplier<HttpResponse<String>> call) {
		Cache cache = getCache();
		if (!isCacheable(cache, context)) {
			return call;
		}
		String key = cacheKey(context, request);
		return () -> {
			CachedResponse cached = cache.get(key);
			boolean sameVariant = cached != null && matchesVary(cached, request);
			boolean differentVariantCached = cached != null && !sameVariant;
			if (sameVariant && cached.isFresh()) {
				return toSyntheticResponse(cached);
			}
			if (sameVariant && cached.isWithinStaleWhileRevalidateWindow()) {
				triggerBackgroundRevalidation(cache, key, cached, request, call, negativeCacheTtlMillis());
				return toSyntheticResponse(cached);
			}
			if (sameVariant) {
				applyRevalidationHeaders(request, cached);
			}
			return reconcileCache(cache, key, sameVariant ? cached : null, differentVariantCached, call.get(),
					request, negativeCacheTtlMillis());
		};
	}

	private static volatile ExecutorService staleWhileRevalidateExecutor;

	/**
	 * Returns the shared background executor used to revalidate a
	 * stale-while-revalidate entry for a <em>synchronous</em> call, created
	 * lazily on first use - a {@code CompletableFuture}-returning call needs
	 * no such executor at all, since it's already async (see
	 * {@link #wrapWithCacheAsync}). Threads are daemon threads, so they never
	 * keep the JVM alive on their own.
	 */
	private static ExecutorService staleWhileRevalidateExecutor() {
		ExecutorService executor = staleWhileRevalidateExecutor;
		if (executor == null) {
			synchronized (CacheCoordinator.class) {
				executor = staleWhileRevalidateExecutor;
				if (executor == null) {
					executor = Executors.newCachedThreadPool(runnable -> {
						Thread thread = new Thread(runnable, "rip-stale-while-revalidate");
						thread.setDaemon(true);
						return thread;
					});
					staleWhileRevalidateExecutor = executor;
				}
			}
		}
		return executor;
	}

	/**
	 * Fires the real network call in the background to refresh
	 * {@code staleEntry}, without blocking the caller that's already being
	 * handed the stale entry immediately. A failed revalidation (a thrown
	 * exception, a non-2xx/non-304 response with no caching headers, etc.)
	 * is silently swallowed - {@code staleEntry} simply keeps being served
	 * until it ages out of its own stale-while-revalidate window too, the
	 * same as if this background attempt had never run.
	 */
	private static void triggerBackgroundRevalidation(Cache cache, String key, CachedResponse staleEntry,
			HttpRequest<?> request, Supplier<HttpResponse<String>> call, Long negativeCacheTtlMillis) {
		applyRevalidationHeaders(request, staleEntry);
		staleWhileRevalidateExecutor().execute(() -> {
			try {
				reconcileCache(cache, key, staleEntry, false, call.get(), request, negativeCacheTtlMillis);
			} catch (RuntimeException e) {
				// Best-effort - see this method's own javadoc.
			}
		});
	}

	/**
	 * The async counterpart of {@link #wrapWithCache}, for a
	 * {@code CompletableFuture}-returning call.
	 *
	 * @param request the request about to be sent
	 * @param context this call's context
	 * @param call    the real, asynchronous network call
	 * @return {@code call} unchanged if caching doesn't apply here,
	 *         otherwise a wrapping supplier that may complete immediately
	 *         with a cached response instead of invoking {@code call} at all
	 */
	Supplier<CompletableFuture<HttpResponse<String>>> wrapWithCacheAsync(HttpRequest<?> request,
			RequestContext context, Supplier<CompletableFuture<HttpResponse<String>>> call) {
		Cache cache = getCache();
		if (!isCacheable(cache, context)) {
			return call;
		}
		String key = cacheKey(context, request);
		return () -> {
			CachedResponse cached = cache.get(key);
			boolean sameVariant = cached != null && matchesVary(cached, request);
			boolean differentVariantCached = cached != null && !sameVariant;
			if (sameVariant && cached.isFresh()) {
				return CompletableFuture.completedFuture(toSyntheticResponse(cached));
			}
			if (sameVariant && cached.isWithinStaleWhileRevalidateWindow()) {
				applyRevalidationHeaders(request, cached);
				// Fire-and-forget: the revalidation result only ever updates
				// the cache for the next call, so it's never joined against
				// the response already being returned below.
				call.get().thenAccept(response -> {
					try {
						reconcileCache(cache, key, cached, false, response, request, negativeCacheTtlMillis());
					} catch (RuntimeException e) {
						// Best-effort - see triggerBackgroundRevalidation's own javadoc.
					}
				});
				return CompletableFuture.completedFuture(toSyntheticResponse(cached));
			}
			if (sameVariant) {
				applyRevalidationHeaders(request, cached);
			}
			CachedResponse staleEntry = sameVariant ? cached : null;
			return call.get().thenApply(response -> reconcileCache(cache, key, staleEntry, differentVariantCached,
					response, request, negativeCacheTtlMillis()));
		};
	}

	private static boolean isCacheable(Cache cache, RequestContext context) {
		return cache != null && context.getHttpMethod() == HTTPMethod.GET
				&& !Boolean.TRUE.equals(context.getAttribute(NO_CACHE_ATTRIBUTE));
	}

	/**
	 * Computes the cache key for this call, via {@link Cache#key}. By
	 * default, reads the URL off {@code request} rather than
	 * {@code context.getUrl()}, since the latter is only the
	 * path-template-resolved URL captured before {@code @QueryParam}/
	 * {@code @QueryMap} are applied (see
	 * {@code RequestExecutor.processRestRequest}), while {@code request}
	 * (already fully built by the time this runs) reflects the exact URL,
	 * query string included, that will actually go out on the wire - so two
	 * calls to the same path differing only by query string don't collide on
	 * one cache entry. When {@link #cacheKeyIncludesQueryString()} is
	 * {@code false}, deliberately keys on {@code context.getUrl()} instead -
	 * every query string variant of the same path then shares one entry, for
	 * an endpoint whose query params don't affect the response (e.g. an
	 * analytics/tracking param) and whose caller would rather trade that
	 * precision for a higher hit rate.
	 */
	private String cacheKey(RequestContext context, HttpRequest<?> request) {
		String url = cacheKeyIncludesQueryString() ? request.getUrl() : context.getUrl();
		return Cache.key(context.getHttpMethod(), url);
	}

	private static void applyRevalidationHeaders(HttpRequest<?> request, CachedResponse cached) {
		String etag = cached.getHeader("ETag");
		if (etag != null) {
			request.headerReplace("If-None-Match", etag);
		}
		String lastModified = cached.getHeader("Last-Modified");
		if (lastModified != null) {
			request.headerReplace("If-Modified-Since", lastModified);
		}
	}

	/**
	 * Reconciles a real network response against {@code staleEntry} (the
	 * previously-cached entry for this exact request's {@code Vary}
	 * variant, if any) once a call has actually gone out - either because
	 * there was nothing cached, a different variant was cached, or a stale
	 * entry needed revalidating. A {@code 404} negatively cached for
	 * {@code negativeCacheTtlMillis} (see {@link #negativeCacheTtlMillis()})
	 * is stored for exactly that long, regardless of any
	 * {@code Cache-Control}/{@code ETag}/{@code Last-Modified} of its own. A
	 * {@code 304 Not Modified} against a known stale entry refreshes its
	 * freshness window and hands back its stored body unchanged; any other
	 * outcome stores {@code key} per the response's own
	 * {@code Cache-Control}/{@code ETag}/{@code Last-Modified} (snapshotting
	 * this request's values for whatever its {@code Vary} header names), or
	 * evicts it - unless {@code leaveExistingEntryAlone} is set, since
	 * evicting then would wrongly discard a still-valid, different variant
	 * this call has nothing to do with.
	 */
	private static HttpResponse<String> reconcileCache(Cache cache, String key, CachedResponse staleEntry,
			boolean leaveExistingEntryAlone, HttpResponse<String> response, HttpRequest<?> request,
			Long negativeCacheTtlMillis) {
		Map<String, List<String>> responseHeaders = ResponseDecoder.toHeaderMap(response.getHeaders());
		if (response.getStatus() == 304 && staleEntry != null) {
			CachedResponse refreshed = new CachedResponse(staleEntry.getStatus(), staleEntry.getHeaders(),
					staleEntry.getBody(), freshUntil(responseHeaders), staleEntry.getVaryRequestHeaders(),
					staleWhileRevalidateUntil(responseHeaders));
			cache.put(key, refreshed);
			return toSyntheticResponse(refreshed);
		}
		if (response.getStatus() == 404 && negativeCacheTtlMillis != null) {
			Map<String, String> varySnapshot = captureVaryValues(request, varyHeaderNames(responseHeaders));
			long freshUntil = System.currentTimeMillis() + negativeCacheTtlMillis;
			cache.put(key,
					new CachedResponse(response.getStatus(), responseHeaders, response.getBody(), freshUntil, varySnapshot));
			return response;
		}
		if (ResponseDecoder.isSuccessStatus(response.getStatus()) && isStorable(responseHeaders)) {
			Map<String, String> varySnapshot = captureVaryValues(request, varyHeaderNames(responseHeaders));
			cache.put(key, new CachedResponse(response.getStatus(), responseHeaders, response.getBody(),
					freshUntil(responseHeaders), varySnapshot, staleWhileRevalidateUntil(responseHeaders)));
		} else if (!leaveExistingEntryAlone) {
			cache.evict(key);
		}
		return response;
	}

	private static boolean isStorable(Map<String, List<String>> headers) {
		CacheDirectives directives = CacheDirectives.parse(firstHeader(headers, "Cache-Control"));
		if (directives.noStore || isWildcardVary(headers)) {
			return false;
		}
		return directives.maxAgeSeconds != null || directives.staleWhileRevalidateSeconds != null
				|| firstHeader(headers, "ETag") != null || firstHeader(headers, "Last-Modified") != null;
	}

	/**
	 * Whether {@code cached} is usable at all for the current request - its
	 * response had no {@code Vary} header (matches every request), or this
	 * request's current values for every header {@code Vary} named are
	 * identical to the ones snapshotted when {@code cached} was stored.
	 */
	private static boolean matchesVary(CachedResponse cached, HttpRequest<?> request) {
		for (Map.Entry<String, String> varyHeader : cached.getVaryRequestHeaders().entrySet()) {
			String currentValue = request.getHeaders().getFirst(varyHeader.getKey());
			if (!Objects.equals(varyHeader.getValue(), currentValue)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * A {@code Vary: *} response varies unpredictably (by definition,
	 * un-cacheable via header comparison) and must never be stored - the
	 * one {@code Vary} value that means "don't cache this at all" rather
	 * than "cache one variant per combination of these header values".
	 */
	private static boolean isWildcardVary(Map<String, List<String>> headers) {
		for (String name : varyHeaderNames(headers)) {
			if (name.equals("*")) {
				return true;
			}
		}
		return false;
	}

	private static List<String> varyHeaderNames(Map<String, List<String>> headers) {
		String vary = firstHeader(headers, "Vary");
		if (vary == null) {
			return Collections.emptyList();
		}
		List<String> names = new ArrayList<>();
		for (String name : vary.split(",")) {
			String trimmed = name.trim();
			if (!trimmed.isEmpty()) {
				names.add(trimmed);
			}
		}
		return names;
	}

	private static Map<String, String> captureVaryValues(HttpRequest<?> request, List<String> varyHeaderNames) {
		if (varyHeaderNames.isEmpty()) {
			return Collections.emptyMap();
		}
		Map<String, String> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		for (String name : varyHeaderNames) {
			values.put(name, request.getHeaders().getFirst(name));
		}
		return values;
	}

	private static long freshUntil(Map<String, List<String>> headers) {
		CacheDirectives directives = CacheDirectives.parse(firstHeader(headers, "Cache-Control"));
		if (!directives.noCache && directives.maxAgeSeconds != null) {
			return System.currentTimeMillis() + directives.maxAgeSeconds * 1000L;
		}
		return System.currentTimeMillis(); // no (usable) freshness window - always revalidate
	}

	/**
	 * Computes an entry's stale-while-revalidate deadline: {@link #freshUntil}
	 * plus the response's own {@code stale-while-revalidate=N} seconds, or
	 * simply {@link #freshUntil} unchanged (no window at all) if the response
	 * named no such directive.
	 */
	private static long staleWhileRevalidateUntil(Map<String, List<String>> headers) {
		CacheDirectives directives = CacheDirectives.parse(firstHeader(headers, "Cache-Control"));
		long freshUntil = freshUntil(headers);
		return directives.staleWhileRevalidateSeconds != null
				? freshUntil + directives.staleWhileRevalidateSeconds * 1000L
				: freshUntil;
	}

	private static String firstHeader(Map<String, List<String>> headers, String name) {
		List<String> values = headers.get(name);
		return values == null || values.isEmpty() ? null : values.get(0);
	}

	/** Parsed {@code Cache-Control} response directives relevant to caching a {@code GET}. */
	private static final class CacheDirectives {
		final boolean noStore;
		final boolean noCache;
		final Long maxAgeSeconds;
		final Long staleWhileRevalidateSeconds;

		private CacheDirectives(boolean noStore, boolean noCache, Long maxAgeSeconds,
				Long staleWhileRevalidateSeconds) {
			this.noStore = noStore;
			this.noCache = noCache;
			this.maxAgeSeconds = maxAgeSeconds;
			this.staleWhileRevalidateSeconds = staleWhileRevalidateSeconds;
		}

		static CacheDirectives parse(String headerValue) {
			if (headerValue == null) {
				return new CacheDirectives(false, false, null, null);
			}
			boolean noStore = false;
			boolean noCache = false;
			Long maxAgeSeconds = null;
			Long staleWhileRevalidateSeconds = null;
			for (String directive : headerValue.split(",")) {
				String trimmed = directive.trim().toLowerCase(Locale.ROOT);
				if (trimmed.equals("no-store")) {
					noStore = true;
				} else if (trimmed.equals("no-cache")) {
					noCache = true;
				} else if (trimmed.startsWith("max-age=")) {
					maxAgeSeconds = parseSeconds(trimmed.substring("max-age=".length()).trim());
				} else if (trimmed.startsWith("stale-while-revalidate=")) {
					staleWhileRevalidateSeconds = parseSeconds(
							trimmed.substring("stale-while-revalidate=".length()).trim());
				}
			}
			return new CacheDirectives(noStore, noCache, maxAgeSeconds, staleWhileRevalidateSeconds);
		}

		private static Long parseSeconds(String value) {
			try {
				return Math.max(0L, Long.parseLong(value));
			} catch (NumberFormatException e) {
				return null; // malformed - fail open, same as no directive at all
			}
		}
	}

	private static HttpResponse<String> toSyntheticResponse(CachedResponse cached) {
		kong.unirest.Headers headers = new kong.unirest.Headers();
		cached.getHeaders().forEach((name, values) -> values.forEach(value -> headers.add(name, value)));
		return new CachedHttpResponse<>(cached.getStatus(), headers, cached.getBody());
	}

	/**
	 * A {@code kong.unirest.HttpResponse} backed by a {@link CachedResponse}
	 * instead of an actual network round trip - handed to the same
	 * {@code decodeOrThrow}/{@code notifyAfterResponse}/{@code wrapResponse}
	 * machinery a real response would go through, so a cache hit is
	 * decoded, reported to interceptors, and wrapped in a
	 * {@code RipResponse} exactly like any other response. Only
	 * {@link #getStatus()}/{@link #getBody()}/{@link #getHeaders()} are ever
	 * actually exercised by that machinery; the rest of this interface is
	 * implemented plainly (a cached entry is never itself a failure status,
	 * since only a successful response is ever stored).
	 */
	private static final class CachedHttpResponse<T> implements HttpResponse<T> {
		private final int status;
		private final kong.unirest.Headers headers;
		private final T body;

		CachedHttpResponse(int status, kong.unirest.Headers headers, T body) {
			this.status = status;
			this.headers = headers;
			this.body = body;
		}

		@Override
		public int getStatus() {
			return status;
		}

		@Override
		public String getStatusText() {
			return "";
		}

		@Override
		public kong.unirest.Headers getHeaders() {
			return headers;
		}

		@Override
		public T getBody() {
			return body;
		}

		@Override
		public Optional<UnirestParsingException> getParsingError() {
			return Optional.empty();
		}

		@Override
		public <V> V mapBody(Function<T, V> func) {
			return func.apply(body);
		}

		@Override
		public <V> HttpResponse<V> map(Function<T, V> func) {
			return new CachedHttpResponse<>(status, headers, func.apply(body));
		}

		@Override
		public HttpResponse<T> ifSuccess(Consumer<HttpResponse<T>> consumer) {
			if (isSuccess()) {
				consumer.accept(this);
			}
			return this;
		}

		@Override
		public HttpResponse<T> ifFailure(Consumer<HttpResponse<T>> consumer) {
			if (!isSuccess()) {
				consumer.accept(this);
			}
			return this;
		}

		@Override
		public <E> HttpResponse<T> ifFailure(Class<? extends E> type, Consumer<HttpResponse<E>> consumer) {
			return this;
		}

		@Override
		public boolean isSuccess() {
			return status >= 200 && status < 300;
		}

		@Override
		public <E> E mapError(Class<? extends E> type) {
			return null;
		}

		@Override
		public Cookies getCookies() {
			return new Cookies();
		}

		@Override
		public HttpRequestSummary getRequestSummary() {
			return new HttpRequestSummary() {
				@Override
				public HttpMethod getHttpMethod() {
					return HttpMethod.GET;
				}

				@Override
				public String getUrl() {
					return "";
				}

				@Override
				public String getRawPath() {
					return "";
				}

				@Override
				public String asString() {
					return "GET";
				}
			};
		}
	}

}
