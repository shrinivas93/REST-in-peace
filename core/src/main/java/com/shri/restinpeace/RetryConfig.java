package com.shri.restinpeace;

/**
 * Per-client default retry policy for {@link RIP#getClient(Class, RipClientConfig)} -
 * applied to any call whose method has no {@code @Retry} of its own (and
 * whose interface has no interface-level {@code @Retry} default either -
 * see that annotation's own javadoc for the interface-level shape), for a
 * client whose retry policy is only known at runtime instead of fixed at
 * compile time (e.g. more aggressive retries against a known-flakier
 * staging environment than what the interface's own {@code @Retry}
 * annotations declare for production).
 *
 * <pre>
 * UserApi stagingApi = RIP.getClient(UserApi.class, RipClientConfig.builder()
 *         .baseUrl(stagingBaseUrl)
 *         .retry(RetryConfig.builder().times(5).delayMillis(500).build())
 *         .build());
 * </pre>
 *
 * A method's own {@code @Retry} (or its interface's) always wins when
 * present - this is only the fallback for a method with neither, mirroring
 * the precedence {@code @Timeout}/{@link RipClientConfig} already establish
 * (method-level annotation beats the client-wide config).
 */
public final class RetryConfig {

	private final int times;
	private final long delayMillis;
	private final double backoffMultiplier;
	private final double jitterFactor;
	private final int[] retryOnStatus;
	private final boolean idempotent;

	private RetryConfig(Builder builder) {
		this.times = builder.times;
		this.delayMillis = builder.delayMillis;
		this.backoffMultiplier = builder.backoffMultiplier;
		this.jitterFactor = builder.jitterFactor;
		this.retryOnStatus = builder.retryOnStatus;
		this.idempotent = builder.idempotent;
	}

	/**
	 * Starts building a new retry config, defaulting to the exact same
	 * values {@code @Retry}'s own annotation defaults use.
	 *
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Returns the maximum number of attempts.
	 *
	 * @return the maximum number of attempts
	 */
	public int getTimes() {
		return times;
	}

	/**
	 * Returns the initial retry delay.
	 *
	 * @return the initial delay in milliseconds
	 */
	public long getDelayMillis() {
		return delayMillis;
	}

	/**
	 * Returns the backoff multiplier.
	 *
	 * @return the backoff multiplier
	 */
	public double getBackoffMultiplier() {
		return backoffMultiplier;
	}

	/**
	 * Returns the jitter fraction.
	 *
	 * @return the jitter fraction
	 */
	public double getJitterFactor() {
		return jitterFactor;
	}

	/**
	 * Returns the retryable HTTP status codes.
	 *
	 * @return the retryable status codes
	 */
	public int[] getRetryOnStatus() {
		return retryOnStatus;
	}

	/**
	 * Returns whether a stable {@code Idempotency-Key} is sent.
	 *
	 * @return whether to send a stable {@code Idempotency-Key}
	 */
	public boolean isIdempotent() {
		return idempotent;
	}

	/** Builds a {@link RetryConfig}. */
	public static final class Builder {

		private int times = 3;
		private long delayMillis = 200;
		private double backoffMultiplier = 2.0;
		private double jitterFactor = 0.0;
		private int[] retryOnStatus = { 429, 502, 503, 504 };
		private boolean idempotent = false;

		private Builder() {
		}

		/**
		 * Sets the maximum number of attempts in total.
		 *
		 * @param times the maximum number of attempts; must be at least 1
		 * @return this builder
		 */
		public Builder times(int times) {
			if (times < 1) {
				throw new IllegalArgumentException("times must be at least 1.");
			}
			this.times = times;
			return this;
		}

		/**
		 * Sets how long to wait before the first retry.
		 *
		 * @param delayMillis the initial delay in milliseconds
		 * @return this builder
		 */
		public Builder delayMillis(long delayMillis) {
			this.delayMillis = delayMillis;
			return this;
		}

		/**
		 * Sets the factor the delay is multiplied by after each retry.
		 *
		 * @param backoffMultiplier the backoff multiplier
		 * @return this builder
		 */
		public Builder backoffMultiplier(double backoffMultiplier) {
			this.backoffMultiplier = backoffMultiplier;
			return this;
		}

		/**
		 * Sets the jitter fraction applied to each computed delay.
		 *
		 * @param jitterFactor the jitter fraction; must be between 0.0 and
		 *                     1.0 inclusive
		 * @return this builder
		 */
		public Builder jitterFactor(double jitterFactor) {
			if (jitterFactor < 0.0 || jitterFactor > 1.0) {
				throw new IllegalArgumentException("jitterFactor must be between 0.0 and 1.0 inclusive.");
			}
			this.jitterFactor = jitterFactor;
			return this;
		}

		/**
		 * Sets the HTTP status codes that count as a failure worth retrying.
		 *
		 * @param retryOnStatus the retryable status codes
		 * @return this builder
		 */
		public Builder retryOnStatus(int... retryOnStatus) {
			this.retryOnStatus = retryOnStatus.clone();
			return this;
		}

		/**
		 * Sets whether to send a stable {@code Idempotency-Key}.
		 *
		 * @param idempotent whether to send a stable {@code Idempotency-Key}
		 * @return this builder
		 */
		public Builder idempotent(boolean idempotent) {
			this.idempotent = idempotent;
			return this;
		}

		/**
		 * Builds the config.
		 *
		 * @return the built config
		 */
		public RetryConfig build() {
			return new RetryConfig(this);
		}
	}

}
