package com.shri.restinpeace;

/**
 * Escape hatch for {@link RipClientConfig.Builder#circuitBreaker(CircuitBreakerProvider)} -
 * delegates the actual open/closed decision to an already-running circuit
 * breaker instance (resilience4j or otherwise) instead of RIP's own
 * built-in {@link CircuitBreakerConfig}-driven state machine. See
 * {@code docs/design/circuit-breaker-bulkhead.md} §5 for the full
 * "build-your-own default, pluggable override" reasoning - RIP never takes
 * a hard dependency on resilience4j (or anything else) to support this;
 * only this small SPI, which a consumer implements themselves.
 *
 * <pre>
 * CircuitBreaker r4jBreaker = CircuitBreaker.ofDefaults("payment-api");
 *
 * RipClientConfig config = RipClientConfig.builder()
 *         .circuitBreaker(new CircuitBreakerProvider() {
 *             public boolean tryAcquirePermission() {
 *                 return r4jBreaker.tryAcquirePermission();
 *             }
 *             public void onSuccess(long durationNanos, int statusCode) {
 *                 if (statusCode &gt;= 500) {
 *                     r4jBreaker.onError(durationNanos, TimeUnit.NANOSECONDS,
 *                             new RuntimeException("HTTP " + statusCode));
 *                 } else {
 *                     r4jBreaker.onSuccess(durationNanos, TimeUnit.NANOSECONDS);
 *                 }
 *             }
 *             public void onError(long durationNanos, Throwable t) {
 *                 r4jBreaker.onError(durationNanos, TimeUnit.NANOSECONDS, t);
 *             }
 *         })
 *         .build());
 * </pre>
 *
 * <p>
 * Only comes into play when explicitly configured this way - a client
 * configured with {@link CircuitBreakerConfig} instead uses RIP's own
 * built-in implementation, unaffected. Setting one clears the other on the
 * builder, the same "last call wins" shape
 * {@link CircuitBreakerConfig.Builder}'s own overloaded
 * {@code slidingWindowSize} setters already use.
 */
public interface CircuitBreakerProvider {

	/**
	 * Called before every attempt (once per {@code @Retry} attempt too, the
	 * same per-attempt granularity RIP's own built-in breaker uses) to ask
	 * whether the call should proceed at all. Assumed non-blocking - the
	 * same assumption resilience4j's own {@code CircuitBreaker#tryAcquirePermission()}
	 * makes - so it's called directly on the caller's thread on both the
	 * sync and async dispatch paths.
	 *
	 * @return {@code true} to let the call proceed; {@code false} to refuse
	 *         it immediately with a
	 *         {@link com.shri.restinpeace.exception.CircuitOpenException},
	 *         without ever attempting the network call
	 */
	boolean tryAcquirePermission();

	/**
	 * Called once a permitted call completes with an actual HTTP response -
	 * any status code, since RIP itself makes no assumption about what an
	 * external breaker considers a failure. Classify {@code statusCode}
	 * yourself (as the example above does) if the underlying breaker's own
	 * config expects a failing status reported through {@link #onError}
	 * instead.
	 *
	 * @param durationNanos how long the call took
	 * @param statusCode    the response's HTTP status code
	 */
	void onSuccess(long durationNanos, int statusCode);

	/**
	 * Called once a permitted call fails at the transport level (a
	 * connection refused, a timeout - no response at all).
	 *
	 * @param durationNanos how long the call took before failing
	 * @param t             the transport-level failure
	 */
	void onError(long durationNanos, Throwable t);

}
