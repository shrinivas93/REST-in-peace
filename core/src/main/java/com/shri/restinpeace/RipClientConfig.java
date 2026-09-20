package com.shri.restinpeace;

import java.util.Collections;
import java.util.List;

import com.shri.restinpeace.cache.Cache;
import com.shri.restinpeace.interceptor.RequestInterceptor;

import kong.unirest.ObjectMapper;

/**
 * Per-client settings for {@link RIP#getClient(Class, RipClientConfig)} -
 * base URL, connect/read timeout, proxy, JSON {@code ObjectMapper}, cache,
 * interceptors, and a default retry policy - for a {@code @RestClient}
 * whose environment differs from every other client's,
 * since {@code kong.unirest.Unirest}'s own global config is shared by every
 * RIP client that doesn't ask for its own. A method's {@link
 * com.shri.restinpeace.annotation.timeout.Timeout @Timeout} overrides this
 * config's timeout when both are present, the same way an absolute method
 * URL overrides {@link #getBaseUrl()}; a method's (or its interface's)
 * {@link com.shri.restinpeace.annotation.retry.Retry @Retry} wins over this
 * config's {@link #getRetry()} the same way.
 *
 * <pre>
 * UserApi prodApi = RIP.getClient(UserApi.class, RipClientConfig.builder()
 *         .baseUrl(prodBaseUrl)
 *         .connectTimeoutMillis(2_000)
 *         .readTimeoutMillis(10_000)
 *         .build());
 * </pre>
 *
 * Setting a connect/read timeout, a proxy, or an {@code objectMapper} gives
 * the client its own dedicated {@code kong.unirest.UnirestInstance} (its own
 * connection pool) instead of sharing the app-wide static {@code Unirest}
 * client - a client built with only {@link #getBaseUrl()} set keeps sharing
 * the static client, same as {@link RIP#getClient(Class, String)}.
 */
public final class RipClientConfig {

	private final String baseUrl;
	private final Integer connectTimeoutMillis;
	private final Integer readTimeoutMillis;
	private final String proxyHost;
	private final int proxyPort;
	private final String proxyUsername;
	private final String proxyPassword;
	private final ObjectMapper objectMapper;
	private final Cache cache;
	private final Boolean cacheKeyIncludesQueryString;
	private final Long negativeCacheTtlMillis;
	private final Integer retryBudgetMaxRetries;
	private final Long retryBudgetWindowMillis;
	private final List<RequestInterceptor> interceptors;
	private final RetryConfig retry;
	private final CircuitBreakerConfig circuitBreaker;
	private final CircuitBreakerProvider circuitBreakerProvider;
	private final BulkheadConfig bulkhead;
	private final BulkheadProvider bulkheadProvider;

	private RipClientConfig(Builder builder) {
		this.baseUrl = builder.baseUrl;
		this.connectTimeoutMillis = builder.connectTimeoutMillis;
		this.readTimeoutMillis = builder.readTimeoutMillis;
		this.proxyHost = builder.proxyHost;
		this.proxyPort = builder.proxyPort;
		this.proxyUsername = builder.proxyUsername;
		this.proxyPassword = builder.proxyPassword;
		this.objectMapper = builder.objectMapper;
		this.cache = builder.cache;
		this.cacheKeyIncludesQueryString = builder.cacheKeyIncludesQueryString;
		this.negativeCacheTtlMillis = builder.negativeCacheTtlMillis;
		this.retryBudgetMaxRetries = builder.retryBudgetMaxRetries;
		this.retryBudgetWindowMillis = builder.retryBudgetWindowMillis;
		this.interceptors = builder.interceptors;
		this.retry = builder.retry;
		this.circuitBreaker = builder.circuitBreaker;
		this.circuitBreakerProvider = builder.circuitBreakerProvider;
		this.bulkhead = builder.bulkhead;
		this.bulkheadProvider = builder.bulkheadProvider;
	}

	/**
	 * Starts building a new config.
	 *
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Returns the runtime base URL.
	 *
	 * @return the runtime base URL, or {@code null} to fall back to
	 *         {@code @BaseUrl} on the interface
	 */
	public String getBaseUrl() {
		return baseUrl;
	}

	/**
	 * Returns the connect timeout.
	 *
	 * @return the connect timeout in milliseconds, or {@code null} to use
	 *         the shared client's configured default
	 */
	public Integer getConnectTimeoutMillis() {
		return connectTimeoutMillis;
	}

	/**
	 * Returns the read (socket) timeout.
	 *
	 * @return the read (socket) timeout in milliseconds, or {@code null} to
	 *         use the shared client's configured default
	 */
	public Integer getReadTimeoutMillis() {
		return readTimeoutMillis;
	}

	/**
	 * Returns the proxy host.
	 *
	 * @return the proxy host, or {@code null} if no proxy is configured
	 */
	public String getProxyHost() {
		return proxyHost;
	}

	/**
	 * Returns the proxy port.
	 *
	 * @return the proxy port; meaningless if {@link #getProxyHost()} is {@code null}
	 */
	public int getProxyPort() {
		return proxyPort;
	}

	/**
	 * Returns the proxy username.
	 *
	 * @return the proxy username, or {@code null} for an unauthenticated proxy
	 */
	public String getProxyUsername() {
		return proxyUsername;
	}

	/**
	 * Returns the proxy password.
	 *
	 * @return the proxy password, or {@code null} for an unauthenticated proxy
	 */
	public String getProxyPassword() {
		return proxyPassword;
	}

	/**
	 * Returns the JSON {@code ObjectMapper} for this client.
	 *
	 * @return the JSON {@code ObjectMapper} for this client, or {@code null}
	 *         to use the shared client's configured default (Unirest's own
	 *         Gson-backed {@code JsonObjectMapper}, unless changed via
	 *         {@link RIP#setObjectMapper(ObjectMapper)})
	 */
	public ObjectMapper getObjectMapper() {
		return objectMapper;
	}

	/**
	 * Returns the {@code Cache} for this client.
	 *
	 * @return this client's own {@code Cache}, or {@code null} to fall back
	 *         to the shared default set via {@link RIP#setCache(Cache)} (or
	 *         no caching at all, if that's never called either)
	 */
	public Cache getCache() {
		return cache;
	}

	/**
	 * Returns whether this client's cache key includes the request's query
	 * string.
	 *
	 * @return whether this client's cache key includes the query string, or
	 *         {@code null} to fall back to the shared default set via
	 *         {@link RIP#setCacheKeyIncludesQueryString(boolean)} (which
	 *         itself defaults to {@code true} if never called)
	 */
	public Boolean getCacheKeyIncludesQueryString() {
		return cacheKeyIncludesQueryString;
	}

	/**
	 * Returns how long this client negatively caches a confirmed {@code 404}.
	 *
	 * @return the negative-cache TTL in milliseconds, or {@code null} to fall
	 *         back to the shared default set via
	 *         {@link RIP#setNegativeCacheTtlMillis(long)} (which itself
	 *         defaults to no negative caching at all if never called)
	 */
	public Long getNegativeCacheTtlMillis() {
		return negativeCacheTtlMillis;
	}

	/**
	 * Returns this client's retry-budget capacity.
	 *
	 * @return the maximum number of retries available at once (and refilled
	 *         back up to over {@link #getRetryBudgetWindowMillis()}), or
	 *         {@code null} for no cap beyond each call's own
	 *         {@code @Retry#times()}
	 */
	public Integer getRetryBudgetMaxRetries() {
		return retryBudgetMaxRetries;
	}

	/**
	 * Returns this client's retry-budget refill window.
	 *
	 * @return how long a fully-drained retry budget takes to refill back to
	 *         {@link #getRetryBudgetMaxRetries()}, in milliseconds, or
	 *         {@code null} if no retry budget is configured
	 */
	public Long getRetryBudgetWindowMillis() {
		return retryBudgetWindowMillis;
	}

	/**
	 * Returns this client's own interceptors.
	 *
	 * @return this client's own interceptors, run in addition to (not instead
	 *         of) every globally registered {@link RIP#addInterceptor
	 *         interceptor} - empty if none were set
	 */
	public List<RequestInterceptor> getInterceptors() {
		return interceptors;
	}

	/**
	 * Returns this client's default retry policy.
	 *
	 * @return this client's default retry policy, applied to a call whose
	 *         method (and interface) has no {@code @Retry} of its own, or
	 *         {@code null} for no retrying at all in that case
	 */
	public RetryConfig getRetry() {
		return retry;
	}

	/**
	 * Returns this client's circuit breaker.
	 *
	 * @return this client's circuit breaker config, or {@code null} for no
	 *         circuit breaker at all (every call always attempted, the
	 *         default)
	 */
	public CircuitBreakerConfig getCircuitBreaker() {
		return circuitBreaker;
	}

	/**
	 * Returns this client's external circuit breaker provider.
	 *
	 * @return this client's {@link CircuitBreakerProvider}, or {@code null}
	 *         if this client instead uses {@link #getCircuitBreaker()} (or
	 *         neither is set)
	 */
	public CircuitBreakerProvider getCircuitBreakerProvider() {
		return circuitBreakerProvider;
	}

	/**
	 * Returns this client's bulkhead.
	 *
	 * @return this client's bulkhead config, or {@code null} for no
	 *         concurrency cap at all (the default)
	 */
	public BulkheadConfig getBulkhead() {
		return bulkhead;
	}

	/**
	 * Returns this client's external bulkhead provider.
	 *
	 * @return this client's {@link BulkheadProvider}, or {@code null} if
	 *         this client instead uses {@link #getBulkhead()} (or neither is
	 *         set)
	 */
	public BulkheadProvider getBulkheadProvider() {
		return bulkheadProvider;
	}

	/** Builds a {@link RipClientConfig}. */
	public static final class Builder {

		private String baseUrl;
		private Integer connectTimeoutMillis;
		private Integer readTimeoutMillis;
		private String proxyHost;
		private int proxyPort;
		private String proxyUsername;
		private String proxyPassword;
		private ObjectMapper objectMapper;
		private Cache cache;
		private Boolean cacheKeyIncludesQueryString;
		private Long negativeCacheTtlMillis;
		private Integer retryBudgetMaxRetries;
		private Long retryBudgetWindowMillis;
		private List<RequestInterceptor> interceptors = Collections.emptyList();
		private RetryConfig retry;
		private CircuitBreakerConfig circuitBreaker;
		private CircuitBreakerProvider circuitBreakerProvider;
		private BulkheadConfig bulkhead;
		private BulkheadProvider bulkheadProvider;

		private Builder() {
		}

		/**
		 * Sets the runtime base URL.
		 *
		 * @param baseUrl the runtime base URL to resolve relative method URLs
		 *                against
		 * @return this builder
		 */
		public Builder baseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
			return this;
		}

		/**
		 * Sets the connect timeout.
		 *
		 * @param connectTimeoutMillis the connect timeout in milliseconds; must
		 *                             not be negative
		 * @return this builder
		 */
		public Builder connectTimeoutMillis(int connectTimeoutMillis) {
			if (connectTimeoutMillis < 0) {
				throw new IllegalArgumentException("connectTimeoutMillis must not be negative.");
			}
			this.connectTimeoutMillis = connectTimeoutMillis;
			return this;
		}

		/**
		 * Sets the read (socket) timeout.
		 *
		 * @param readTimeoutMillis the read (socket) timeout in milliseconds;
		 *                          must not be negative
		 * @return this builder
		 */
		public Builder readTimeoutMillis(int readTimeoutMillis) {
			if (readTimeoutMillis < 0) {
				throw new IllegalArgumentException("readTimeoutMillis must not be negative.");
			}
			this.readTimeoutMillis = readTimeoutMillis;
			return this;
		}

		/**
		 * Sets an unauthenticated proxy.
		 *
		 * @param host the proxy host
		 * @param port the proxy port
		 * @return this builder
		 */
		public Builder proxy(String host, int port) {
			return proxy(host, port, null, null);
		}

		/**
		 * Sets an authenticated proxy.
		 *
		 * @param host     the proxy host
		 * @param port     the proxy port
		 * @param username the proxy username
		 * @param password the proxy password
		 * @return this builder
		 */
		public Builder proxy(String host, int port, String username, String password) {
			this.proxyHost = host;
			this.proxyPort = port;
			this.proxyUsername = username;
			this.proxyPassword = password;
			return this;
		}

		/**
		 * Sets the JSON {@code ObjectMapper} for this client.
		 *
		 * @param objectMapper the JSON {@code ObjectMapper} for this client
		 * @return this builder
		 */
		public Builder objectMapper(ObjectMapper objectMapper) {
			this.objectMapper = objectMapper;
			return this;
		}

		/**
		 * Sets the {@code Cache} for this client, overriding the shared
		 * default set via {@link RIP#setCache(Cache)} for this client only.
		 * Only {@code GET} responses are ever cached, and only when the
		 * response itself carries a {@code Cache-Control max-age}, an
		 * {@code ETag}, or a {@code Last-Modified} to honor.
		 *
		 * @param cache the cache to use for this client
		 * @return this builder
		 */
		public Builder cache(Cache cache) {
			this.cache = cache;
			return this;
		}

		/**
		 * Sets whether this client's cache key includes the request's query
		 * string, overriding the shared default set via
		 * {@link RIP#setCacheKeyIncludesQueryString(boolean)} (itself
		 * {@code true} by default, matching RIP's own established behavior)
		 * for this client only. Turn this off for an endpoint whose query
		 * params don't affect the response (e.g. an analytics/tracking
		 * param), so every query-string variant of the same path shares one
		 * cache entry instead of each getting its own - trading precision
		 * for a higher hit rate. Has no effect unless this client also has a
		 * {@link Cache} configured (its own via {@link #cache(Cache)}, or
		 * the shared default).
		 *
		 * @param cacheKeyIncludesQueryString whether this client's cache key
		 *                                    includes the query string
		 * @return this builder
		 */
		public Builder cacheKeyIncludesQueryString(boolean cacheKeyIncludesQueryString) {
			this.cacheKeyIncludesQueryString = cacheKeyIncludesQueryString;
			return this;
		}

		/**
		 * Opts this client into negatively caching a confirmed {@code 404},
		 * overriding the shared default set via
		 * {@link RIP#setNegativeCacheTtlMillis(long)} for this client only -
		 * so a client that already asked once for a resource that doesn't
		 * exist stops hammering the downstream asking again, for
		 * {@code ttlMillis} - regardless of whether the {@code 404} response
		 * itself carries any {@code Cache-Control}/{@code ETag}/
		 * {@code Last-Modified} at all (unlike every other cached status,
		 * which is only ever stored when the server's own headers say so).
		 * Has no effect unless this client also has a {@link Cache}
		 * configured (its own via {@link #cache(Cache)}, or the shared
		 * default), and is skipped the same way by {@code @NoCache}.
		 *
		 * @param ttlMillis how long a confirmed {@code 404} stays negatively
		 *                  cached, in milliseconds; must be positive
		 * @return this builder
		 */
		public Builder negativeCacheTtlMillis(long ttlMillis) {
			if (ttlMillis <= 0) {
				throw new IllegalArgumentException("ttlMillis must be positive.");
			}
			this.negativeCacheTtlMillis = ttlMillis;
			return this;
		}

		/**
		 * Caps the *total* number of retries this client performs across
		 * every call within a rolling window - not a per-call limit, which
		 * is still each call's own {@code @Retry#times()} (or this config's
		 * {@link #retry(RetryConfig)} default). Addresses a retry storm
		 * amplifying an outage: many concurrently failing calls each
		 * retrying up to their own {@code times()} independently can
		 * multiply an already-struggling downstream's request volume, where
		 * a shared budget caps the aggregate instead. Tokens refill
		 * continuously (a token-bucket, not a fixed window that resets in
		 * one burst): starting full at {@code maxRetries}, and regaining
		 * {@code maxRetries / windowMillis} tokens per elapsed millisecond,
		 * capped at {@code maxRetries}. Once the budget is exhausted, a call
		 * that would otherwise retry instead returns (or throws) its current
		 * outcome immediately, exactly as if it had reached its own
		 * {@code times()} - no different from an ordinary give-up. Not
		 * called at all (the default) means no cap beyond each call's own
		 * {@code times()}, byte-for-byte today's behavior.
		 *
		 * @param maxRetries   the bucket's capacity - the maximum number of
		 *                     retries available at once; must be positive
		 * @param windowMillis how long a fully-drained bucket takes to
		 *                     refill back to {@code maxRetries}, in
		 *                     milliseconds; must be positive
		 * @return this builder
		 */
		public Builder retryBudget(int maxRetries, long windowMillis) {
			if (maxRetries <= 0) {
				throw new IllegalArgumentException("maxRetries must be positive.");
			}
			if (windowMillis <= 0) {
				throw new IllegalArgumentException("windowMillis must be positive.");
			}
			this.retryBudgetMaxRetries = maxRetries;
			this.retryBudgetWindowMillis = windowMillis;
			return this;
		}

		/**
		 * Sets this client's own interceptors, run in addition to (not
		 * instead of) every globally registered {@link RIP#addInterceptor
		 * interceptor} - for a concern specific to this one client (e.g. this
		 * service's own auth scheme) instead of every call RIP ever makes.
		 * Global interceptors run first in {@code beforeRequest} (bracketing
		 * everything, including this client's own) and last in
		 * {@code afterResponse}, the same "onion" ordering
		 * {@link com.shri.restinpeace.interceptor.RequestInterceptor}'s own
		 * javadoc describes for interceptors registered globally.
		 *
		 * @param interceptors this client's own interceptors
		 * @return this builder
		 */
		public Builder interceptors(List<RequestInterceptor> interceptors) {
			this.interceptors = interceptors == null ? Collections.emptyList() : interceptors;
			return this;
		}

		/**
		 * Sets this client's default retry policy - applied to a call whose
		 * method (and interface) has no {@code @Retry} of its own, which
		 * always wins over this when present. See {@link RetryConfig}.
		 *
		 * @param retry this client's default retry policy, or {@code null}
		 *              for no retrying at all in that case
		 * @return this builder
		 */
		public Builder retry(RetryConfig retry) {
			this.retry = retry;
			return this;
		}

		/**
		 * Sets this client's circuit breaker - stops even attempting calls
		 * to this client once its failure rate crosses a threshold, for a
		 * cooldown period, instead of paying the cost of finding out each
		 * one would have failed too. See {@link CircuitBreakerConfig}'s own
		 * javadoc (and {@code docs/design/circuit-breaker-bulkhead.md}) for
		 * the full state machine and every default.
		 *
		 * @param circuitBreaker this client's circuit breaker config, or
		 *                       {@code null} for no circuit breaker at all
		 *                       (the default)
		 * @return this builder
		 */
		public Builder circuitBreaker(CircuitBreakerConfig circuitBreaker) {
			this.circuitBreaker = circuitBreaker;
			this.circuitBreakerProvider = null;
			return this;
		}

		/**
		 * Sets this client's circuit breaker to delegate its open/closed
		 * decision to an already-running external breaker instance
		 * (resilience4j or otherwise) instead of RIP's own built-in
		 * {@link CircuitBreakerConfig}-driven state machine. See
		 * {@link CircuitBreakerProvider}'s own javadoc for the full
		 * reasoning and a worked example. Overrides any earlier
		 * {@link #circuitBreaker(CircuitBreakerConfig)} call, the same
		 * "last call wins" shape {@link CircuitBreakerConfig.Builder}'s own
		 * overloaded {@code slidingWindowSize} setters already use.
		 *
		 * @param circuitBreakerProvider this client's external circuit
		 *                               breaker provider, or {@code null}
		 *                               for no circuit breaker at all (the
		 *                               default)
		 * @return this builder
		 */
		public Builder circuitBreaker(CircuitBreakerProvider circuitBreakerProvider) {
			this.circuitBreakerProvider = circuitBreakerProvider;
			this.circuitBreaker = null;
			return this;
		}

		/**
		 * Sets this client's bulkhead - caps how many calls to this client
		 * can be in flight at once, so one slow or hung downstream can't
		 * starve every other call sharing the same connection pool/thread
		 * capacity. See {@link BulkheadConfig}'s own javadoc (and
		 * {@code docs/design/circuit-breaker-bulkhead.md}) for the full
		 * reasoning and every default.
		 *
		 * @param bulkhead this client's bulkhead config, or {@code null} for
		 *                 no concurrency cap at all (the default)
		 * @return this builder
		 */
		public Builder bulkhead(BulkheadConfig bulkhead) {
			this.bulkhead = bulkhead;
			this.bulkheadProvider = null;
			return this;
		}

		/**
		 * Sets this client's bulkhead to delegate its admission decision to
		 * an already-running external bulkhead instance (resilience4j or
		 * otherwise) instead of RIP's own built-in {@link BulkheadConfig}-driven
		 * {@link java.util.concurrent.Semaphore}. See
		 * {@link BulkheadProvider}'s own javadoc for the full reasoning and
		 * a worked example. Overrides any earlier {@link #bulkhead(BulkheadConfig)}
		 * call, the same "last call wins" shape this config's
		 * {@link #circuitBreaker(CircuitBreakerProvider)} overload also
		 * uses.
		 *
		 * @param bulkheadProvider this client's external bulkhead provider,
		 *                         or {@code null} for no concurrency cap at
		 *                         all (the default)
		 * @return this builder
		 */
		public Builder bulkhead(BulkheadProvider bulkheadProvider) {
			this.bulkheadProvider = bulkheadProvider;
			this.bulkhead = null;
			return this;
		}

		/**
		 * Builds the config.
		 *
		 * @return the built config
		 */
		public RipClientConfig build() {
			return new RipClientConfig(this);
		}
	}

}
