package com.shri.restinpeace;

/**
 * Escape hatch for {@link RipClientConfig.Builder#bulkhead(BulkheadProvider)} -
 * delegates the actual admission decision to an already-running bulkhead
 * instance (resilience4j or otherwise) instead of RIP's own built-in
 * {@link BulkheadConfig}-driven {@link java.util.concurrent.Semaphore}. See
 * {@code docs/design/circuit-breaker-bulkhead.md} §5 for the full
 * "build-your-own default, pluggable override" reasoning.
 *
 * <pre>
 * Bulkhead r4jBulkhead = Bulkhead.ofDefaults("payment-api");
 *
 * RipClientConfig config = RipClientConfig.builder()
 *         .bulkhead(new BulkheadProvider() {
 *             public boolean tryAcquirePermission() {
 *                 return r4jBulkhead.tryAcquirePermission();
 *             }
 *             public void onComplete() {
 *                 r4jBulkhead.onComplete();
 *             }
 *         })
 *         .build());
 * </pre>
 *
 * <p>
 * Only comes into play when explicitly configured this way - a client
 * configured with {@link BulkheadConfig} instead uses RIP's own built-in
 * implementation, unaffected. Setting one clears the other on the builder,
 * the same "last call wins" shape {@link CircuitBreakerConfig.Builder}'s
 * own overloaded {@code slidingWindowSize} setters already use.
 *
 * <p>
 * Unlike RIP's own {@link BulkheadConfig#getMaxWaitDurationMillis()},
 * {@link #tryAcquirePermission()} is assumed non-blocking - the same
 * assumption resilience4j's own {@code Bulkhead#tryAcquirePermission()}
 * makes - so it's called directly on the caller's thread on both the sync
 * and async dispatch paths, with no background-thread hop.
 */
public interface BulkheadProvider {

	/**
	 * Called before every attempt to ask whether the call should proceed at
	 * all.
	 *
	 * @return {@code true} to let the call proceed; {@code false} to refuse
	 *         it immediately with a
	 *         {@link com.shri.restinpeace.exception.BulkheadFullException},
	 *         without ever attempting the network call
	 */
	boolean tryAcquirePermission();

	/** Called once a permitted call completes, successfully or not. */
	void onComplete();

}
