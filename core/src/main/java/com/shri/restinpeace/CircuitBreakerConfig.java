package com.shri.restinpeace;

import java.time.Duration;
import java.util.function.IntPredicate;

/**
 * Per-client circuit breaker for {@link RIP#getClient(Class, RipClientConfig)} -
 * stops even attempting calls to a downstream that's shown itself to be
 * failing, for a cooldown period, once its failure rate crosses a threshold.
 * See {@code docs/design/circuit-breaker-bulkhead.md} for the full design,
 * personas, and the reasoning behind every default below.
 *
 * <p>
 * The sliding window is <b>count-based</b> (the last {@link #getSlidingWindowSize()}
 * <i>calls</i>) by default, not time-based (the last N seconds) - a
 * count-based window is deterministic to test and doesn't misbehave for a
 * low-traffic client, where "the last 30 seconds" might contain zero or one
 * call. The trip condition is always a <b>failure rate</b> (a percentage of
 * the window), never a raw failure count, gated by
 * {@link #getMinimumNumberOfCalls()} so a rate is never evaluated off a
 * statistically meaningless sample.
 *
 * <pre>
 * UserApi api = RIP.getClient(UserApi.class, RipClientConfig.builder()
 *         .circuitBreaker(CircuitBreakerConfig.builder()
 *                 .slidingWindowSize(20)
 *                 .minimumNumberOfCalls(10)
 *                 .failureRateThreshold(50)
 *                 .waitDurationInOpenState(Duration.ofSeconds(30))
 *                 .permittedCallsInHalfOpenState(3)
 *                 .build())
 *         .build());
 * </pre>
 *
 * <p>
 * A {@link SlidingWindowType#TIME_BASED} window (the last N seconds,
 * regardless of call volume) is also supported via
 * {@link Builder#slidingWindowSize(Duration)} - unlike resilience4j's own
 * bare {@code int}-always-means-seconds shape, this takes a real
 * {@link Duration}, consistent with {@link #getWaitDurationInOpenStateMillis()}
 * already being duration-typed in this same config.
 */
public final class CircuitBreakerConfig {

	/** Whether the sliding window counts the last N calls or the last N seconds. */
	public enum SlidingWindowType {
		/** The last {@link CircuitBreakerConfig#getSlidingWindowSize()} calls - the default. */
		COUNT_BASED,
		/** The last {@link CircuitBreakerConfig#getSlidingWindowDurationMillis()} of wall-clock time. */
		TIME_BASED
	}

	private final SlidingWindowType slidingWindowType;
	private final int slidingWindowSize;
	private final long slidingWindowDurationMillis;
	private final int minimumNumberOfCalls;
	private final int failureRateThreshold;
	private final long waitDurationInOpenStateMillis;
	private final int permittedCallsInHalfOpenState;
	private final IntPredicate recordFailureForStatus;

	private CircuitBreakerConfig(Builder builder) {
		this.slidingWindowType = builder.slidingWindowType;
		this.slidingWindowSize = builder.slidingWindowSize;
		this.slidingWindowDurationMillis = builder.slidingWindowDurationMillis;
		this.minimumNumberOfCalls = builder.minimumNumberOfCalls;
		this.failureRateThreshold = builder.failureRateThreshold;
		this.waitDurationInOpenStateMillis = builder.waitDurationInOpenStateMillis;
		this.permittedCallsInHalfOpenState = builder.permittedCallsInHalfOpenState;
		this.recordFailureForStatus = builder.recordFailureForStatus;
	}

	/**
	 * Starts building a new config, defaulting to a count-based, 20-call
	 * window; a 50% failure-rate threshold, evaluated once at least 10 calls
	 * have been recorded; a 30-second open-state cooldown; 3 permitted
	 * half-open trial calls; and treating any 5xx response (or a transport
	 * failure) as a breaker failure.
	 *
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Returns the sliding window's type.
	 *
	 * @return whether the window is count- or time-based
	 */
	public SlidingWindowType getSlidingWindowType() {
		return slidingWindowType;
	}

	/**
	 * Returns the count-based window's size.
	 *
	 * @return the number of most-recent calls the window tracks; meaningless
	 *         if {@link #getSlidingWindowType()} is {@link SlidingWindowType#TIME_BASED}
	 */
	public int getSlidingWindowSize() {
		return slidingWindowSize;
	}

	/**
	 * Returns the time-based window's duration.
	 *
	 * @return how far back the window looks, in milliseconds; meaningless if
	 *         {@link #getSlidingWindowType()} is {@link SlidingWindowType#COUNT_BASED}
	 */
	public long getSlidingWindowDurationMillis() {
		return slidingWindowDurationMillis;
	}

	/**
	 * Returns the minimum sample size before a failure rate is evaluated.
	 *
	 * @return the minimum number of calls that must be in the window before
	 *         {@link #getFailureRateThreshold()} is ever checked
	 */
	public int getMinimumNumberOfCalls() {
		return minimumNumberOfCalls;
	}

	/**
	 * Returns the failure-rate trip threshold.
	 *
	 * @return the failure rate, as a percentage from 1 to 100, that trips the
	 *         breaker from closed to open
	 */
	public int getFailureRateThreshold() {
		return failureRateThreshold;
	}

	/**
	 * Returns how long the breaker stays open before trialing recovery.
	 *
	 * @return the open-state cooldown, in milliseconds
	 */
	public long getWaitDurationInOpenStateMillis() {
		return waitDurationInOpenStateMillis;
	}

	/**
	 * Returns how many trial calls are permitted while half-open.
	 *
	 * @return the number of calls permitted through while half-open, before
	 *         deciding whether to close or re-open
	 */
	public int getPermittedCallsInHalfOpenState() {
		return permittedCallsInHalfOpenState;
	}

	/**
	 * Returns the predicate deciding which HTTP status codes count as a
	 * breaker failure.
	 *
	 * @return the status-code failure predicate; a transport-level failure
	 *         (no response at all) always counts as a failure regardless of
	 *         this predicate, since there's no status code to test
	 */
	public IntPredicate getRecordFailureForStatus() {
		return recordFailureForStatus;
	}

	/** Builds a {@link CircuitBreakerConfig}. */
	public static final class Builder {

		private SlidingWindowType slidingWindowType = SlidingWindowType.COUNT_BASED;
		private int slidingWindowSize = 20;
		private long slidingWindowDurationMillis;
		private int minimumNumberOfCalls = 10;
		private int failureRateThreshold = 50;
		private long waitDurationInOpenStateMillis = 30_000;
		private int permittedCallsInHalfOpenState = 3;
		private IntPredicate recordFailureForStatus = status -> status >= 500;

		private Builder() {
		}

		/**
		 * Selects a count-based window of {@code size} calls - the default
		 * shape. Overrides any earlier {@link #slidingWindowSize(Duration)}
		 * call.
		 *
		 * @param size the number of most-recent calls to track; must be at
		 *             least 1
		 * @return this builder
		 */
		public Builder slidingWindowSize(int size) {
			if (size < 1) {
				throw new IllegalArgumentException("size must be at least 1.");
			}
			this.slidingWindowType = SlidingWindowType.COUNT_BASED;
			this.slidingWindowSize = size;
			return this;
		}

		/**
		 * Selects a time-based window of the last {@code duration} - for a
		 * consumer who specifically wants "rate over the last N seconds
		 * regardless of call volume" rather than the count-based default.
		 * Overrides any earlier {@link #slidingWindowSize(int)} call.
		 *
		 * @param duration how far back the window looks; must be positive
		 * @return this builder
		 */
		public Builder slidingWindowSize(Duration duration) {
			if (duration == null || duration.isNegative() || duration.isZero()) {
				throw new IllegalArgumentException("duration must be positive.");
			}
			this.slidingWindowType = SlidingWindowType.TIME_BASED;
			this.slidingWindowDurationMillis = duration.toMillis();
			return this;
		}

		/**
		 * Sets the minimum number of calls that must be in the window before
		 * a failure rate is ever evaluated, so the breaker never trips off a
		 * statistically meaningless sample (e.g. 1 failure out of 2 calls is
		 * technically 50%, but shouldn't trip a breaker tuned for a 20-call
		 * window).
		 *
		 * @param minimumNumberOfCalls the minimum sample size; must be at
		 *                             least 1
		 * @return this builder
		 */
		public Builder minimumNumberOfCalls(int minimumNumberOfCalls) {
			if (minimumNumberOfCalls < 1) {
				throw new IllegalArgumentException("minimumNumberOfCalls must be at least 1.");
			}
			this.minimumNumberOfCalls = minimumNumberOfCalls;
			return this;
		}

		/**
		 * Sets the failure-rate threshold that trips the breaker open.
		 *
		 * @param failureRateThreshold the failure rate, as a percentage from
		 *                             1 to 100
		 * @return this builder
		 */
		public Builder failureRateThreshold(int failureRateThreshold) {
			if (failureRateThreshold < 1 || failureRateThreshold > 100) {
				throw new IllegalArgumentException("failureRateThreshold must be between 1 and 100 inclusive.");
			}
			this.failureRateThreshold = failureRateThreshold;
			return this;
		}

		/**
		 * Sets how long the breaker stays open before trialing recovery via
		 * a half-open state.
		 *
		 * @param waitDurationInOpenState the open-state cooldown; must be
		 *                                positive
		 * @return this builder
		 */
		public Builder waitDurationInOpenState(Duration waitDurationInOpenState) {
			if (waitDurationInOpenState == null || waitDurationInOpenState.isNegative()
					|| waitDurationInOpenState.isZero()) {
				throw new IllegalArgumentException("waitDurationInOpenState must be positive.");
			}
			this.waitDurationInOpenStateMillis = waitDurationInOpenState.toMillis();
			return this;
		}

		/**
		 * Sets how many trial calls are let through while half-open, once
		 * the open-state cooldown elapses, before deciding whether to close
		 * (recovered) or re-open (still failing) based on their own failure
		 * rate.
		 *
		 * @param permittedCallsInHalfOpenState the number of trial calls;
		 *                                      must be at least 1
		 * @return this builder
		 */
		public Builder permittedCallsInHalfOpenState(int permittedCallsInHalfOpenState) {
			if (permittedCallsInHalfOpenState < 1) {
				throw new IllegalArgumentException("permittedCallsInHalfOpenState must be at least 1.");
			}
			this.permittedCallsInHalfOpenState = permittedCallsInHalfOpenState;
			return this;
		}

		/**
		 * Sets which HTTP status codes count as a breaker failure - a
		 * genuine downstream failure (5xx, the default), not an
		 * application-level expected outcome (e.g. a {@code 404} from an
		 * existence check, the same kind of outcome negative caching already
		 * treats as a valid answer rather than an error). A transport-level
		 * failure (connection refused, timeout - no response at all) always
		 * counts as a failure regardless of this predicate, since there's no
		 * status code to test it against.
		 *
		 * @param recordFailureForStatus the status-code failure predicate
		 * @return this builder
		 */
		public Builder recordFailureForStatus(IntPredicate recordFailureForStatus) {
			if (recordFailureForStatus == null) {
				throw new IllegalArgumentException("recordFailureForStatus must not be null.");
			}
			this.recordFailureForStatus = recordFailureForStatus;
			return this;
		}

		/**
		 * Builds the config.
		 *
		 * @return the built config
		 */
		public CircuitBreakerConfig build() {
			return new CircuitBreakerConfig(this);
		}
	}

}
