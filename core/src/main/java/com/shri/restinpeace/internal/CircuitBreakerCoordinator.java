package com.shri.restinpeace.internal;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.shri.restinpeace.CircuitBreakerConfig;
import com.shri.restinpeace.CircuitBreakerConfig.SlidingWindowType;
import com.shri.restinpeace.CircuitBreakerProvider;
import com.shri.restinpeace.exception.CircuitOpenException;

import kong.unirest.HttpResponse;

/**
 * Per-client circuit breaker state machine (CLOSED → OPEN → HALF_OPEN →
 * CLOSED/OPEN), one instance per {@link RequestExecutor} - see
 * {@code docs/design/circuit-breaker-bulkhead.md}.
 *
 * <p>
 * Deliberately <b>not</b> a {@link com.shri.restinpeace.interceptor.RequestInterceptor}
 * (an earlier sketch assumed it would be): an interceptor's {@code afterResponse}
 * is only ever notified for a call that actually receives a response - a
 * transport-level failure (connection refused, timeout, no response at all)
 * never reaches it, the same documented gap
 * {@link com.shri.restinpeace.interceptor.MetricsInterceptor} already has for
 * the same reason. That gap is exactly wrong for a feature whose whole point
 * is catching "the downstream is completely down." Instead, this wraps the
 * same {@code Supplier<HttpResponse<B>>} chain {@link CacheCoordinator} and
 * {@link InterceptorDispatcher}'s short-circuit already wrap - as the
 * <i>outermost</i> layer, so an open breaker skips cache lookups, short-circuit
 * checks, and the network call entirely - which sees both a real response
 * (via its status code) and a thrown transport exception (via a
 * {@code try/catch} around the inner call) uniformly.
 *
 * <p>
 * Re-invoked once per {@code @Retry} attempt (this wraps the exact supplier
 * {@link RetryExecutor} calls {@code .get()} on for each attempt), so a
 * retried call reports one outcome per RIP-level attempt to the sliding
 * window, not one for the whole retried sequence - mirroring
 * {@link com.shri.restinpeace.interceptor.MetricsInterceptor}'s own
 * per-attempt sample precedent. See {@link RetryExecutor} for the
 * complementary decision to never retry a {@link CircuitOpenException}
 * itself, since every attempt would fail identically until the cooldown
 * elapses.
 *
 * <p>
 * Also the adapter for a {@link CircuitBreakerProvider}-backed client (see
 * {@code docs/design/circuit-breaker-bulkhead.md} §5) - when configured
 * this way, every state-machine decision below is delegated straight to
 * the external provider instead of this class's own {@link State} machine,
 * which then goes unused entirely.
 */
final class CircuitBreakerCoordinator {

	private enum State {
		CLOSED, OPEN, HALF_OPEN
	}

	private static final class TimestampedOutcome {
		final long timestampMillis;
		final boolean isFailure;

		TimestampedOutcome(long timestampMillis, boolean isFailure) {
			this.timestampMillis = timestampMillis;
			this.isFailure = isFailure;
		}
	}

	private final CircuitBreakerConfig config;

	// Non-null only when this client delegates to an external breaker (see
	// docs/design/circuit-breaker-bulkhead.md §5) instead of using the
	// built-in state machine below - config and provider are never both
	// non-null. Checked first in both wrap methods so a provider-backed
	// instance is never mistaken for unconfigured just because config is null.
	private final CircuitBreakerProvider provider;

	// COUNT_BASED window - a fixed-capacity ring buffer of failure flags,
	// with a running failure count kept in sync incrementally so the failure
	// rate is always an O(1) lookup rather than a rescan of the whole window.
	private final boolean[] failureFlags;
	private int nextSlot;
	private int filledSlots;
	private int failureCount;

	// TIME_BASED window - pruned lazily (only on a new recordOutcome, not on
	// a background timer) since the rate is only ever consulted immediately
	// after adding a new outcome.
	private final ArrayDeque<TimestampedOutcome> timeWindow = new ArrayDeque<>();
	private int timeWindowFailures;

	private State state = State.CLOSED;
	private long openedAtEpochMillis;
	private int halfOpenPermitsRemaining;
	private int halfOpenCompleted;
	private int halfOpenFailures;

	/**
	 * @param config this client's circuit breaker config, or {@code null} if
	 *               none is configured - {@link #wrapWithCircuitBreaker} is
	 *               then a no-op passthrough, the same
	 *               always-constructed-but-inert-when-unconfigured shape
	 *               {@link CacheCoordinator} already uses.
	 */
	CircuitBreakerCoordinator(CircuitBreakerConfig config) {
		this.config = config;
		this.provider = null;
		this.failureFlags = config != null && config.getSlidingWindowType() == SlidingWindowType.COUNT_BASED
				? new boolean[config.getSlidingWindowSize()]
				: null;
	}

	/**
	 * @param provider this client's external circuit breaker provider (see
	 *                 {@link CircuitBreakerProvider}'s own javadoc), or
	 *                 {@code null} if none is configured
	 */
	CircuitBreakerCoordinator(CircuitBreakerProvider provider) {
		this.config = null;
		this.provider = provider;
		this.failureFlags = null;
	}

	/**
	 * Wraps a network-call supplier so it's refused immediately (no cache
	 * lookup, no short-circuit check, no network call) while this client's
	 * breaker is open, and so its outcome - success, a failing status code,
	 * or a thrown transport exception - is recorded into the sliding window
	 * either way. Must be the outermost wrap around the supplier chain
	 * {@link RetryExecutor} re-invokes per attempt.
	 *
	 * @param call the real (possibly cache-/short-circuit-wrapped) call
	 * @return a wrapping supplier that may refuse the call outright instead
	 *         of ever invoking {@code call}
	 */
	<B> Supplier<HttpResponse<B>> wrapWithCircuitBreaker(Supplier<HttpResponse<B>> call) {
		if (provider != null) {
			return wrapWithProvider(call);
		}
		if (config == null) {
			return call;
		}
		return () -> {
			checkPermission();
			try {
				HttpResponse<B> response = call.get();
				recordOutcome(!config.getRecordFailureForStatus().test(response.getStatus()));
				return response;
			} catch (RuntimeException e) {
				recordOutcome(false);
				throw e;
			}
		};
	}

	/**
	 * The {@link #provider}-backed counterpart of the built-in
	 * {@link #wrapWithCircuitBreaker} logic - same shape (check permission,
	 * invoke, report the outcome), but every decision and every recorded
	 * outcome goes straight to the external provider instead of this
	 * coordinator's own state machine.
	 */
	private <B> Supplier<HttpResponse<B>> wrapWithProvider(Supplier<HttpResponse<B>> call) {
		return () -> {
			if (!provider.tryAcquirePermission()) {
				throw new CircuitOpenException(
						"Circuit breaker is open (external provider); refusing call without attempting it.");
			}
			long startNanos = System.nanoTime();
			try {
				HttpResponse<B> response = call.get();
				provider.onSuccess(System.nanoTime() - startNanos, response.getStatus());
				return response;
			} catch (RuntimeException e) {
				provider.onError(System.nanoTime() - startNanos, e);
				throw e;
			}
		};
	}

	/**
	 * The async counterpart of {@link #wrapWithCircuitBreaker}, for the
	 * {@code CompletableFuture} dispatch path. Unlike the sync wrap, this
	 * never throws {@link CircuitOpenException} synchronously from the
	 * returned supplier - {@link #checkPermission()} is instant either way
	 * (a quick {@code synchronized} check, never a real wait), but a
	 * synchronous throw here would be lost if this supplier is ever invoked
	 * from inside a scheduled callback (as {@link RetryExecutor}'s async
	 * retry loop does for attempt 2+), silently hanging the caller's future
	 * forever instead of surfacing the failure. Delivering it as an already-
	 * failed future instead is safe in both places it's invoked from - the
	 * top-level call and a scheduled retry - and is the more idiomatic shape
	 * for a {@code CompletableFuture}-returning API besides.
	 *
	 * @param call the real (possibly cache-/short-circuit-wrapped) async call
	 * @return a wrapping supplier that may return an already-failed future
	 *         instead of ever invoking {@code call}
	 */
	<B> Supplier<CompletableFuture<HttpResponse<B>>> wrapWithCircuitBreakerAsync(
			Supplier<CompletableFuture<HttpResponse<B>>> call) {
		if (provider != null) {
			return wrapWithProviderAsync(call);
		}
		if (config == null) {
			return call;
		}
		return () -> {
			try {
				checkPermission();
			} catch (CircuitOpenException e) {
				CompletableFuture<HttpResponse<B>> failed = new CompletableFuture<>();
				failed.completeExceptionally(e);
				return failed;
			}
			return call.get().whenComplete((response, failure) -> {
				if (failure != null) {
					recordOutcome(false);
				} else {
					recordOutcome(!config.getRecordFailureForStatus().test(response.getStatus()));
				}
			});
		};
	}

	/**
	 * The {@link #provider}-backed counterpart of
	 * {@link #wrapWithCircuitBreakerAsync} - same never-throw-synchronously
	 * shape (see that method's own javadoc for why), but delegating to the
	 * external provider instead of this coordinator's own state machine.
	 */
	private <B> Supplier<CompletableFuture<HttpResponse<B>>> wrapWithProviderAsync(
			Supplier<CompletableFuture<HttpResponse<B>>> call) {
		return () -> {
			if (!provider.tryAcquirePermission()) {
				CompletableFuture<HttpResponse<B>> failed = new CompletableFuture<>();
				failed.completeExceptionally(new CircuitOpenException(
						"Circuit breaker is open (external provider); refusing call without attempting it."));
				return failed;
			}
			long startNanos = System.nanoTime();
			return call.get().whenComplete((response, failure) -> {
				if (failure != null) {
					provider.onError(System.nanoTime() - startNanos, failure);
				} else {
					provider.onSuccess(System.nanoTime() - startNanos, response.getStatus());
				}
			});
		};
	}

	private synchronized void checkPermission() {
		if (state == State.OPEN) {
			long elapsed = System.currentTimeMillis() - openedAtEpochMillis;
			if (elapsed < config.getWaitDurationInOpenStateMillis()) {
				throw new CircuitOpenException(
						"Circuit breaker is open; refusing call without attempting it (cooldown ends in "
								+ (config.getWaitDurationInOpenStateMillis() - elapsed) + "ms).");
			}
			transitionToHalfOpen();
		}
		if (state == State.HALF_OPEN) {
			if (halfOpenPermitsRemaining <= 0) {
				throw new CircuitOpenException(
						"Circuit breaker is half-open with no trial permits remaining; refusing call.");
			}
			halfOpenPermitsRemaining--;
		}
		// CLOSED: always permitted.
	}

	private synchronized void recordOutcome(boolean success) {
		if (state == State.HALF_OPEN) {
			recordHalfOpenOutcome(success);
			return;
		}
		// Only ever CLOSED here: checkPermission() never lets a call through while
		// OPEN without first transitioning to HALF_OPEN (handled above), so there's
		// no third state left to guard against.
		recordWindowOutcome(success);
		if (sampleSize() >= config.getMinimumNumberOfCalls()
				&& failureRatePercent() >= config.getFailureRateThreshold()) {
			transitionToOpen();
		}
	}

	private void recordHalfOpenOutcome(boolean success) {
		halfOpenCompleted++;
		if (!success) {
			halfOpenFailures++;
		}
		if (halfOpenCompleted < config.getPermittedCallsInHalfOpenState()) {
			return;
		}
		int rate = halfOpenFailures * 100 / halfOpenCompleted;
		if (rate >= config.getFailureRateThreshold()) {
			transitionToOpen();
		} else {
			transitionToClosed();
		}
	}

	private void transitionToOpen() {
		state = State.OPEN;
		openedAtEpochMillis = System.currentTimeMillis();
	}

	private void transitionToHalfOpen() {
		state = State.HALF_OPEN;
		halfOpenPermitsRemaining = config.getPermittedCallsInHalfOpenState();
		halfOpenCompleted = 0;
		halfOpenFailures = 0;
	}

	private void transitionToClosed() {
		state = State.CLOSED;
		if (failureFlags != null) {
			nextSlot = 0;
			filledSlots = 0;
			failureCount = 0;
		} else {
			timeWindow.clear();
			timeWindowFailures = 0;
		}
	}

	private void recordWindowOutcome(boolean success) {
		if (failureFlags != null) {
			recordCountWindowOutcome(success);
		} else {
			recordTimeWindowOutcome(success);
		}
	}

	private void recordCountWindowOutcome(boolean success) {
		boolean isFailure = !success;
		if (filledSlots == failureFlags.length) {
			if (failureFlags[nextSlot]) {
				failureCount--;
			}
		} else {
			filledSlots++;
		}
		failureFlags[nextSlot] = isFailure;
		if (isFailure) {
			failureCount++;
		}
		nextSlot = (nextSlot + 1) % failureFlags.length;
	}

	private void recordTimeWindowOutcome(boolean success) {
		long now = System.currentTimeMillis();
		timeWindow.addLast(new TimestampedOutcome(now, !success));
		if (!success) {
			timeWindowFailures++;
		}
		// The entry just added above is always timestamped "now", and
		// CircuitBreakerConfig.Builder#slidingWindowSize(Duration) rejects a
		// non-positive duration, so cutoff is always strictly less than "now" -
		// that entry itself can never be the one pruned here, and the queue can
		// therefore never go empty from this loop. No emptiness check needed.
		long cutoff = now - config.getSlidingWindowDurationMillis();
		while (timeWindow.peekFirst().timestampMillis < cutoff) {
			TimestampedOutcome expired = timeWindow.pollFirst();
			if (expired.isFailure) {
				timeWindowFailures--;
			}
		}
	}

	private int sampleSize() {
		return failureFlags != null ? filledSlots : timeWindow.size();
	}

	// Its one caller always runs this right after recordWindowOutcome() adds an
	// entry, so sampleSize() here is never 0 - no guard needed against dividing
	// by it.
	private int failureRatePercent() {
		int size = sampleSize();
		int failures = failureFlags != null ? failureCount : timeWindowFailures;
		return failures * 100 / size;
	}

}
