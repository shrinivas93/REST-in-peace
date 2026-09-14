package com.shri.restinpeace.internal;

/**
 * A token-bucket cap on the *total* number of retries a client may perform
 * across every call it makes within a rolling time window - not a per-call
 * limit (that's still {@code @Retry#times()}'s own job). One instance is
 * shared by every call going through the same {@link RequestExecutor}
 * (i.e. the same client, for a client constructed once and reused as this
 * library's own documented best practice recommends), so a retry storm
 * across many concurrently failing calls is capped in aggregate instead of
 * each call independently retrying up to its own {@code times()} and
 * multiplying an outage's own request volume.
 *
 * <p>
 * Tokens refill continuously (not in a single burst every window boundary):
 * starting full at {@code maxRetries}, and regaining
 * {@code maxRetries / windowMillis} tokens per elapsed millisecond, capped
 * at {@code maxRetries} - the standard token-bucket shape, computed lazily
 * on every {@link #tryConsume()} rather than via a background thread.
 * Thread-safe: {@link #tryConsume()} is the only mutating operation, and is
 * synchronized.
 */
final class RetryBudget {

	private final int maxRetries;
	private final long windowMillis;
	private double availableTokens;
	private long lastRefillEpochMillis;

	/**
	 * @param maxRetries   the bucket's capacity - the maximum number of
	 *                     retries available at once, and the number it
	 *                     refills back up to over {@code windowMillis}; must
	 *                     be positive
	 * @param windowMillis how long a fully-drained bucket takes to refill
	 *                     back to {@code maxRetries}, in milliseconds; must
	 *                     be positive
	 */
	RetryBudget(int maxRetries, long windowMillis) {
		if (maxRetries <= 0) {
			throw new IllegalArgumentException("maxRetries must be positive.");
		}
		if (windowMillis <= 0) {
			throw new IllegalArgumentException("windowMillis must be positive.");
		}
		this.maxRetries = maxRetries;
		this.windowMillis = windowMillis;
		this.availableTokens = maxRetries;
		this.lastRefillEpochMillis = System.currentTimeMillis();
	}

	/**
	 * Attempts to consume one token - one retry about to be performed.
	 *
	 * @return {@code true} if a token was available (and has now been
	 *         consumed), {@code false} if the bucket is empty and this
	 *         retry must not proceed
	 */
	synchronized boolean tryConsume() {
		refill();
		if (availableTokens >= 1.0) {
			availableTokens -= 1.0;
			return true;
		}
		return false;
	}

	private void refill() {
		long now = System.currentTimeMillis();
		long elapsedMillis = now - lastRefillEpochMillis;
		if (elapsedMillis <= 0) {
			return;
		}
		double refillRatePerMillis = (double) maxRetries / windowMillis;
		availableTokens = Math.min(maxRetries, availableTokens + elapsedMillis * refillRatePerMillis);
		lastRefillEpochMillis = now;
	}

}
