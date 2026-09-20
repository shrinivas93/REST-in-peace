package com.shri.restinpeace;

import java.time.Duration;

/**
 * Per-client concurrency cap for {@link RIP#getClient(Class, RipClientConfig)} -
 * bounds how many calls to this client can be in flight at once, so one slow
 * or hung downstream can't starve every other call sharing the same
 * connection pool/thread capacity. See {@code docs/design/circuit-breaker-bulkhead.md}
 * for the full design, personas, and the reasoning behind every default
 * below.
 *
 * <p>
 * Unlike {@link CircuitBreakerConfig}, this isn't about a downstream's
 * failure rate at all - it's a plain concurrency throttle, tripped by
 * volume alone, regardless of whether calls are succeeding or failing.
 *
 * <pre>
 * UserApi api = RIP.getClient(UserApi.class, RipClientConfig.builder()
 *         .bulkhead(BulkheadConfig.builder()
 *                 .maxConcurrentCalls(25)
 *                 .maxWaitDuration(Duration.ofMillis(500))
 *                 .build())
 *         .build());
 * </pre>
 */
public final class BulkheadConfig {

	private final int maxConcurrentCalls;
	private final long maxWaitDurationMillis;

	private BulkheadConfig(Builder builder) {
		this.maxConcurrentCalls = builder.maxConcurrentCalls;
		this.maxWaitDurationMillis = builder.maxWaitDurationMillis;
	}

	/**
	 * Starts building a new config, defaulting to 25 max concurrent calls and
	 * failing fast (no wait) once that many are already in flight -
	 * resilience4j's own default shape for the same feature.
	 *
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Returns the concurrency cap.
	 *
	 * @return the maximum number of calls to this client permitted in flight
	 *         at once
	 */
	public int getMaxConcurrentCalls() {
		return maxConcurrentCalls;
	}

	/**
	 * Returns how long a call waits for a free permit before being refused.
	 *
	 * @return the maximum wait for a permit, in milliseconds; {@code 0}
	 *         means fail immediately instead of waiting at all
	 */
	public long getMaxWaitDurationMillis() {
		return maxWaitDurationMillis;
	}

	/** Builds a {@link BulkheadConfig}. */
	public static final class Builder {

		private int maxConcurrentCalls = 25;
		private long maxWaitDurationMillis;

		private Builder() {
		}

		/**
		 * Sets the concurrency cap.
		 *
		 * @param maxConcurrentCalls the maximum number of calls to this
		 *                           client permitted in flight at once; must
		 *                           be at least 1
		 * @return this builder
		 */
		public Builder maxConcurrentCalls(int maxConcurrentCalls) {
			if (maxConcurrentCalls < 1) {
				throw new IllegalArgumentException("maxConcurrentCalls must be at least 1.");
			}
			this.maxConcurrentCalls = maxConcurrentCalls;
			return this;
		}

		/**
		 * Sets how long a call waits for a free permit before being refused
		 * with a {@link com.shri.restinpeace.exception.BulkheadFullException} -
		 * a queueing tolerance for a bursty caller (see the high-fan-out
		 * async persona in the design doc) rather than the fail-fast default,
		 * where every permit already being held immediately refuses the call.
		 *
		 * @param maxWaitDuration the maximum wait for a permit; {@link Duration#ZERO}
		 *                        (the default) fails immediately instead of
		 *                        waiting at all; must not be negative
		 * @return this builder
		 */
		public Builder maxWaitDuration(Duration maxWaitDuration) {
			if (maxWaitDuration == null || maxWaitDuration.isNegative()) {
				throw new IllegalArgumentException("maxWaitDuration must not be negative.");
			}
			this.maxWaitDurationMillis = maxWaitDuration.toMillis();
			return this;
		}

		/**
		 * Builds the config.
		 *
		 * @return the built config
		 */
		public BulkheadConfig build() {
			return new BulkheadConfig(this);
		}
	}

}
