package com.shri.restinpeace.internal;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.shri.restinpeace.BulkheadConfig;
import com.shri.restinpeace.exception.BulkheadFullException;
import com.shri.restinpeace.exception.RestInPeaceException;

import kong.unirest.HttpResponse;

/**
 * Per-client concurrency cap for {@link RequestExecutor}, sync path - see
 * {@code docs/design/circuit-breaker-bulkhead.md}. Unlike
 * {@link CircuitBreakerCoordinator}, this has no state machine and needs no
 * external synchronization: a {@link Semaphore} is already thread-safe on
 * its own, so a plain acquire/release around the wrapped call is enough.
 *
 * <p>
 * Wraps the exact same {@code Supplier<HttpResponse<B>>} chain
 * {@link CircuitBreakerCoordinator} wraps, but one layer further in - see
 * {@link RequestExecutor} for the composition order and why: the breaker
 * needs to refuse an already-known-bad call before it ever reaches the
 * bulkhead, so a call that was never going to be attempted doesn't
 * needlessly occupy (or wait for) a permit meant for calls that actually
 * get dispatched.
 */
final class BulkheadCoordinator {

	private final BulkheadConfig config;
	private final Semaphore semaphore;

	/**
	 * @param config this client's bulkhead config, or {@code null} if none
	 *               is configured - {@link #wrapWithBulkhead} is then a
	 *               no-op passthrough, the same
	 *               always-constructed-but-inert-when-unconfigured shape
	 *               {@link CircuitBreakerCoordinator} already uses.
	 */
	BulkheadCoordinator(BulkheadConfig config) {
		this.config = config;
		this.semaphore = config != null ? new Semaphore(config.getMaxConcurrentCalls()) : null;
	}

	/**
	 * Wraps a network-call supplier so it's refused (or made to wait, if
	 * {@link BulkheadConfig#getMaxWaitDurationMillis()} is set) once this
	 * client already has {@link BulkheadConfig#getMaxConcurrentCalls()}
	 * calls in flight - the permit is released once the wrapped call
	 * completes, successfully or not, freeing it for the next waiting call.
	 *
	 * @param call the real (possibly circuit-breaker-/cache-/short-circuit-
	 *             wrapped) call
	 * @return a wrapping supplier that may refuse the call outright instead
	 *         of ever invoking {@code call}
	 */
	<B> Supplier<HttpResponse<B>> wrapWithBulkhead(Supplier<HttpResponse<B>> call) {
		if (config == null) {
			return call;
		}
		return () -> {
			acquireOrThrow();
			try {
				return call.get();
			} finally {
				semaphore.release();
			}
		};
	}

	private void acquireOrThrow() {
		boolean acquired;
		try {
			acquired = config.getMaxWaitDurationMillis() > 0
					? semaphore.tryAcquire(config.getMaxWaitDurationMillis(), TimeUnit.MILLISECONDS)
					: semaphore.tryAcquire();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RestInPeaceException("Interrupted while waiting for a bulkhead permit.", e);
		}
		if (!acquired) {
			throw new BulkheadFullException(String.format(
					"Bulkhead is full (max %d concurrent calls); refusing call without attempting it.",
					config.getMaxConcurrentCalls()));
		}
	}

}
