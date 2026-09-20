package com.shri.restinpeace.internal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.shri.restinpeace.BulkheadConfig;
import com.shri.restinpeace.BulkheadProvider;
import com.shri.restinpeace.exception.BulkheadFullException;
import com.shri.restinpeace.exception.RestInPeaceException;

import kong.unirest.HttpResponse;

/**
 * Per-client concurrency cap for {@link RequestExecutor}, both sync and
 * async dispatch - see {@code docs/design/circuit-breaker-bulkhead.md}.
 * Unlike {@link CircuitBreakerCoordinator}, this has no state machine and
 * needs no external synchronization: a {@link Semaphore} is already
 * thread-safe on its own, so a plain acquire/release around the wrapped
 * call is enough.
 *
 * <p>
 * Wraps the exact same {@code Supplier<HttpResponse<B>>} (or, for async,
 * {@code Supplier<CompletableFuture<HttpResponse<B>>>}) chain
 * {@link CircuitBreakerCoordinator} wraps, but one layer further in - see
 * {@link RequestExecutor} for the composition order and why: the breaker
 * needs to refuse an already-known-bad call before it ever reaches the
 * bulkhead, so a call that was never going to be attempted doesn't
 * needlessly occupy (or wait for) a permit meant for calls that actually
 * get dispatched.
 *
 * <p>
 * Also the adapter for a {@link BulkheadProvider}-backed client (see
 * {@code docs/design/circuit-breaker-bulkhead.md} §5) - when configured
 * this way, every acquire/release call above is delegated straight to the
 * external provider instead of this class's own {@link Semaphore}, which
 * then goes unused ({@code null}) entirely.
 */
final class BulkheadCoordinator {

	// Backs only the async wrap's bounded-wait case (BulkheadConfig#getMaxWaitDurationMillis() > 0) -
	// offloads Semaphore#tryAcquire(long, TimeUnit)'s genuine blocking wait onto a
	// background thread instead of the caller's, so an async caller (e.g. an event-loop
	// thread) gets its CompletableFuture back immediately rather than blocking on it.
	// Shared and cached (not one thread per client) since a wait here is transient -
	// mirrors RetryExecutor's own static, daemon-threaded RETRY_SCHEDULER.
	private static final ExecutorService WAIT_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
		Thread thread = new Thread(runnable, "rip-bulkhead-wait");
		thread.setDaemon(true);
		return thread;
	});

	private final BulkheadConfig config;

	// Non-null only when this client delegates to an external bulkhead (see
	// docs/design/circuit-breaker-bulkhead.md §5) instead of using the
	// built-in Semaphore below - config and provider are never both non-null.
	// Checked first in both wrap methods so a provider-backed instance is
	// never mistaken for unconfigured just because config is null.
	private final BulkheadProvider provider;

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
		this.provider = null;
		this.semaphore = config != null ? new Semaphore(config.getMaxConcurrentCalls()) : null;
	}

	/**
	 * @param provider this client's external bulkhead provider (see
	 *                 {@link BulkheadProvider}'s own javadoc), or
	 *                 {@code null} if none is configured
	 */
	BulkheadCoordinator(BulkheadProvider provider) {
		this.config = null;
		this.provider = provider;
		this.semaphore = null;
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
		if (provider != null) {
			return () -> {
				if (!provider.tryAcquirePermission()) {
					throw new BulkheadFullException(
							"Bulkhead is full (external provider); refusing call without attempting it.");
				}
				try {
					return call.get();
				} finally {
					provider.onComplete();
				}
			};
		}
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

	/**
	 * The async counterpart of {@link #wrapWithBulkhead}, for the
	 * {@code CompletableFuture} dispatch path. Never blocks the caller's
	 * thread waiting for a permit - the same reasoning as
	 * {@link CircuitBreakerCoordinator#wrapWithCircuitBreakerAsync}: a
	 * synchronous throw (or a blocking wait) here could run inside a
	 * scheduled retry callback, which would silently hang the caller's
	 * future forever instead of surfacing the failure. A full bulkhead with
	 * no configured wait fails the returned future immediately (the
	 * underlying {@link Semaphore#tryAcquire()} call is itself instant, so
	 * this needs no thread hop); a configured
	 * {@link BulkheadConfig#getMaxWaitDurationMillis()} offloads the actual
	 * blocking wait onto {@link #WAIT_EXECUTOR} instead of the caller's
	 * thread.
	 *
	 * @param call the real (possibly circuit-breaker-/cache-/short-circuit-
	 *             wrapped) async call
	 * @return a wrapping supplier that may return an already-failed future
	 *         instead of ever invoking {@code call}
	 */
	<B> Supplier<CompletableFuture<HttpResponse<B>>> wrapWithBulkheadAsync(
			Supplier<CompletableFuture<HttpResponse<B>>> call) {
		if (provider != null) {
			return () -> {
				if (!provider.tryAcquirePermission()) {
					CompletableFuture<HttpResponse<B>> failed = new CompletableFuture<>();
					failed.completeExceptionally(new BulkheadFullException(
							"Bulkhead is full (external provider); refusing call without attempting it."));
					return failed;
				}
				return call.get().whenComplete((response, failure) -> provider.onComplete());
			};
		}
		if (config == null) {
			return call;
		}
		return () -> acquireAsync().thenCompose(ignored -> call.get().whenComplete((response, failure) -> semaphore.release()));
	}

	private void acquireOrThrow() {
		if (!tryAcquireBlocking()) {
			throw new BulkheadFullException(fullMessage());
		}
	}

	private boolean tryAcquireBlocking() {
		try {
			return config.getMaxWaitDurationMillis() > 0
					? semaphore.tryAcquire(config.getMaxWaitDurationMillis(), TimeUnit.MILLISECONDS)
					: semaphore.tryAcquire();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RestInPeaceException("Interrupted while waiting for a bulkhead permit.", e);
		}
	}

	private CompletableFuture<Void> acquireAsync() {
		if (config.getMaxWaitDurationMillis() <= 0) {
			CompletableFuture<Void> result = new CompletableFuture<>();
			try {
				acquireOrThrow();
				result.complete(null);
			} catch (RestInPeaceException e) {
				result.completeExceptionally(e);
			}
			return result;
		}
		return CompletableFuture.supplyAsync(this::tryAcquireBlocking, WAIT_EXECUTOR).thenCompose(acquired -> {
			CompletableFuture<Void> result = new CompletableFuture<>();
			if (acquired) {
				result.complete(null);
			} else {
				result.completeExceptionally(new BulkheadFullException(fullMessage()));
			}
			return result;
		});
	}

	private String fullMessage() {
		return String.format("Bulkhead is full (max %d concurrent calls); refusing call without attempting it.",
				config.getMaxConcurrentCalls());
	}

}
