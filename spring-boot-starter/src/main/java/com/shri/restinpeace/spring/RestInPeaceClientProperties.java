package com.shri.restinpeace.spring;

/**
 * One client's settings under {@code rest-in-peace.clients.<name>.*} -
 * connect/read timeout, proxy, circuit breaker, and bulkhead, bound by
 * {@link RestInPeaceClientsRegistrar} straight off the {@code Environment}
 * via Spring Boot's relaxed-binding {@code Binder}, the same eager,
 * bean-registration-time resolution {@code baseUrlProperty} already gets.
 * Plain JavaBean shape (not a Java record) so binding needs no
 * {@code -parameters} compiler flag.
 *
 * <p>
 * Every field is optional: a client with no matching {@code clients.<name>}
 * section keeps sharing the app-wide static Unirest client and its default
 * timeouts, exactly as if this class didn't exist - see
 * {@link com.shri.restinpeace.RipClientConfig}'s own javadoc.
 *
 * <p>
 * {@code circuitBreaker}/{@code bulkhead} only expose the subset of
 * {@code CircuitBreakerConfig}/{@code BulkheadConfig}'s own builder options
 * that are plain scalars a property source can express -
 * {@code CircuitBreakerConfig.Builder#recordFailureForStatus(IntPredicate)}
 * has no property-file equivalent and stays programmatic-only. Only the
 * built-in implementation is reachable this way; a
 * {@code CircuitBreakerProvider}/{@code BulkheadProvider}-backed client
 * (see {@code docs/design/circuit-breaker-bulkhead.md} §5) is a Java object,
 * not a scalar, so it's still wired via {@code RipClientConfig.Builder}
 * directly rather than through these properties.
 */
final class RestInPeaceClientProperties {

	private Integer connectTimeoutMillis;
	private Integer readTimeoutMillis;
	private Proxy proxy;
	private CircuitBreaker circuitBreaker;
	private Bulkhead bulkhead;

	Integer getConnectTimeoutMillis() {
		return connectTimeoutMillis;
	}

	void setConnectTimeoutMillis(Integer connectTimeoutMillis) {
		this.connectTimeoutMillis = connectTimeoutMillis;
	}

	Integer getReadTimeoutMillis() {
		return readTimeoutMillis;
	}

	void setReadTimeoutMillis(Integer readTimeoutMillis) {
		this.readTimeoutMillis = readTimeoutMillis;
	}

	Proxy getProxy() {
		return proxy;
	}

	void setProxy(Proxy proxy) {
		this.proxy = proxy;
	}

	CircuitBreaker getCircuitBreaker() {
		return circuitBreaker;
	}

	void setCircuitBreaker(CircuitBreaker circuitBreaker) {
		this.circuitBreaker = circuitBreaker;
	}

	Bulkhead getBulkhead() {
		return bulkhead;
	}

	void setBulkhead(Bulkhead bulkhead) {
		this.bulkhead = bulkhead;
	}

	/** A client's proxy settings, under {@code clients.<name>.proxy}. */
	static final class Proxy {

		private String host;
		private int port;
		private String username;
		private String password;

		String getHost() {
			return host;
		}

		void setHost(String host) {
			this.host = host;
		}

		int getPort() {
			return port;
		}

		void setPort(int port) {
			this.port = port;
		}

		String getUsername() {
			return username;
		}

		void setUsername(String username) {
			this.username = username;
		}

		String getPassword() {
			return password;
		}

		void setPassword(String password) {
			this.password = password;
		}

	}

	/**
	 * A client's circuit breaker settings, under
	 * {@code clients.<name>.circuit-breaker} - mirrors
	 * {@code CircuitBreakerConfig.Builder}'s own scalar options; any option
	 * left unset here keeps that builder's own default. {@code slidingWindowSize}
	 * and {@code slidingWindowDurationMillis} pick between
	 * {@code CircuitBreakerConfig.Builder#slidingWindowSize(int)} (count-based)
	 * and {@code #slidingWindowSize(Duration)} (time-based) - setting both is
	 * meaningless, so the duration wins if both happen to be set.
	 */
	static final class CircuitBreaker {

		private Integer slidingWindowSize;
		private Long slidingWindowDurationMillis;
		private Integer minimumNumberOfCalls;
		private Integer failureRateThreshold;
		private Long waitDurationInOpenStateMillis;
		private Integer permittedCallsInHalfOpenState;

		Integer getSlidingWindowSize() {
			return slidingWindowSize;
		}

		void setSlidingWindowSize(Integer slidingWindowSize) {
			this.slidingWindowSize = slidingWindowSize;
		}

		Long getSlidingWindowDurationMillis() {
			return slidingWindowDurationMillis;
		}

		void setSlidingWindowDurationMillis(Long slidingWindowDurationMillis) {
			this.slidingWindowDurationMillis = slidingWindowDurationMillis;
		}

		Integer getMinimumNumberOfCalls() {
			return minimumNumberOfCalls;
		}

		void setMinimumNumberOfCalls(Integer minimumNumberOfCalls) {
			this.minimumNumberOfCalls = minimumNumberOfCalls;
		}

		Integer getFailureRateThreshold() {
			return failureRateThreshold;
		}

		void setFailureRateThreshold(Integer failureRateThreshold) {
			this.failureRateThreshold = failureRateThreshold;
		}

		Long getWaitDurationInOpenStateMillis() {
			return waitDurationInOpenStateMillis;
		}

		void setWaitDurationInOpenStateMillis(Long waitDurationInOpenStateMillis) {
			this.waitDurationInOpenStateMillis = waitDurationInOpenStateMillis;
		}

		Integer getPermittedCallsInHalfOpenState() {
			return permittedCallsInHalfOpenState;
		}

		void setPermittedCallsInHalfOpenState(Integer permittedCallsInHalfOpenState) {
			this.permittedCallsInHalfOpenState = permittedCallsInHalfOpenState;
		}

	}

	/**
	 * A client's bulkhead settings, under {@code clients.<name>.bulkhead} -
	 * mirrors {@code BulkheadConfig.Builder}'s own options; either option left
	 * unset here keeps that builder's own default.
	 */
	static final class Bulkhead {

		private Integer maxConcurrentCalls;
		private Long maxWaitDurationMillis;

		Integer getMaxConcurrentCalls() {
			return maxConcurrentCalls;
		}

		void setMaxConcurrentCalls(Integer maxConcurrentCalls) {
			this.maxConcurrentCalls = maxConcurrentCalls;
		}

		Long getMaxWaitDurationMillis() {
			return maxWaitDurationMillis;
		}

		void setMaxWaitDurationMillis(Long maxWaitDurationMillis) {
			this.maxWaitDurationMillis = maxWaitDurationMillis;
		}

	}

}
