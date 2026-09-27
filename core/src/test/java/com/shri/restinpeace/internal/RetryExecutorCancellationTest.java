package com.shri.restinpeace.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import kong.unirest.HttpResponse;

import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.interceptor.RequestContext;

/**
 * Direct unit coverage for {@link RetryExecutor}'s cancellation relay - the
 * Reactor adapters and {@code RequestExecutor} only ever exercise this
 * indirectly, through a real (or {@code MockRestServer}-backed) async HTTP
 * round trip, which can't cheaply isolate exactly which concrete
 * future/task a cancellation actually reaches. These drive
 * {@link RetryExecutor#executeAsyncWithRetry} directly, with a hand-built
 * {@code call} {@link java.util.function.Supplier} standing in for the
 * actual async dispatch, against each of the three things a cancellation
 * can currently be in flight against: no {@code @Retry} at all, the first
 * (and only, in these tests) attempt itself, and a scheduled retry still
 * waiting out its backoff.
 */
class RetryExecutorCancellationTest {

	private final RetryExecutor retryExecutor = new RetryExecutor(
			new InterceptorDispatcher(Collections.emptyList(), new ResponseDecoder(null)));
	private final RequestContext context = new RequestContext(HTTPMethod.GET, "http://example.com");

	@Test
	void cancellingWithNoRetryConfigured_cancelsTheInFlightCallFuture() {
		CompletableFuture<HttpResponse<String>> callFuture = new CompletableFuture<>();

		CompletableFuture<HttpResponse<String>> result = retryExecutor.executeAsyncWithRetry(null, String.class, context,
				() -> callFuture, false, 0, 0L, 1.0, 0.0, new int[0]);
		result.cancel(true);

		assertTrue(callFuture.isCancelled());
	}

	@Test
	void cancellingWhileTheFirstAttemptIsStillInFlight_cancelsThatAttemptsFuture() {
		CompletableFuture<HttpResponse<String>> attemptFuture = new CompletableFuture<>();

		CompletableFuture<HttpResponse<String>> result = retryExecutor.executeAsyncWithRetry(null, String.class, context,
				() -> attemptFuture, true, 3, 300L, 1.0, 0.0, new int[] { 503 });
		result.cancel(true);

		assertTrue(attemptFuture.isCancelled());
	}

	@Test
	void cancellingDuringTheRetryBackoffWait_stopsTheScheduledRetryFromEverRunning() throws InterruptedException {
		AtomicInteger callCount = new AtomicInteger();
		HttpResponse<String> alwaysFailing = new SyntheticHttpResponse<>(503, new kong.unirest.Headers(), "down");

		CompletableFuture<HttpResponse<String>> result = retryExecutor.executeAsyncWithRetry(null, String.class, context,
				() -> {
					callCount.incrementAndGet();
					return CompletableFuture.completedFuture(alwaysFailing);
				}, true, 3, 400L, 1.0, 0.0, new int[] { 503 });

		awaitCallCount(callCount, 1); // the first attempt has landed and failed
		result.cancel(true); // well before the 400ms backoff wait elapses

		Thread.sleep(600); // past the backoff wait - a second attempt would have run by now if not cancelled
		assertEquals(1, callCount.get());
	}

	@Test
	void cancellingTwice_isIdempotentAndStillCancelsOnlyOnce() {
		CompletableFuture<HttpResponse<String>> attemptFuture = new CompletableFuture<>();

		CompletableFuture<HttpResponse<String>> result = retryExecutor.executeAsyncWithRetry(null, String.class, context,
				() -> attemptFuture, true, 3, 300L, 1.0, 0.0, new int[] { 503 });
		result.cancel(true);
		result.cancel(true); // CancellationRelay.cancelCurrent()'s own already-cancelled guard

		assertTrue(attemptFuture.isCancelled());
	}

	/**
	 * Regression coverage for the cancellation-consumes-retry-budget bug: a
	 * still-pending attempt's cancellation (via {@code CancellationRelay})
	 * completes it with a {@code CancellationException}, which was
	 * previously treated as an ordinary retryable failure - consuming a
	 * token from this client's shared {@link RetryBudget} even though no
	 * real HTTP call ever actually failed; only the caller walked away. A
	 * budget of exactly one token proves it: cancelling a pending attempt
	 * must leave that one token untouched for the very next, unrelated call
	 * to use. (Cancelling a real, already-completed, genuinely-failed
	 * attempt during its backoff wait is a different case - that attempt's
	 * own token spend already happened honestly and isn't this bug; see
	 * {@link #cancellingDuringTheRetryBackoffWait_stopsTheScheduledRetryFromEverRunning}.)
	 */
	@Test
	void cancellingAStillPendingAttempt_doesNotConsumeARetryBudgetToken() {
		RetryBudget retryBudget = new RetryBudget(1, 60_000);
		RetryExecutor executorWithBudget = new RetryExecutor(
				new InterceptorDispatcher(Collections.emptyList(), new ResponseDecoder(null)), null, retryBudget);

		CompletableFuture<HttpResponse<String>> attemptFuture = new CompletableFuture<>(); // never completes on its own
		CompletableFuture<HttpResponse<String>> first = executorWithBudget.executeAsyncWithRetry(null, String.class,
				context, () -> attemptFuture, true, 3, 300L, 1.0, 0.0, new int[] { 503 });
		first.cancel(true); // no real attempt ever completed - must not spend the one token

		AtomicInteger secondCallCount = new AtomicInteger();
		CompletableFuture<HttpResponse<String>> second = executorWithBudget.executeAsyncWithRetry(null, String.class,
				context, () -> {
					int attempt = secondCallCount.incrementAndGet();
					HttpResponse<String> response = new SyntheticHttpResponse<>(attempt < 2 ? 503 : 200,
							new kong.unirest.Headers(), attempt < 2 ? "down" : "ok");
					return CompletableFuture.completedFuture(response);
				}, true, 3, 1L, 1.0, 0.0, new int[] { 503 });

		HttpResponse<String> response = second.join();
		assertEquals(200, response.getStatus());
		assertEquals(2, secondCallCount.get());
	}

	@Test
	void notCancelling_stillRetriesNormallyAndSucceeds() {
		AtomicInteger callCount = new AtomicInteger();

		CompletableFuture<HttpResponse<String>> result = retryExecutor.executeAsyncWithRetry(null, String.class, context,
				() -> {
					int attempt = callCount.incrementAndGet();
					HttpResponse<String> response = new SyntheticHttpResponse<>(attempt < 3 ? 503 : 200,
							new kong.unirest.Headers(), attempt < 3 ? "down" : "ok");
					return CompletableFuture.completedFuture(response);
				}, true, 3, 1L, 1.0, 0.0, new int[] { 503 });

		HttpResponse<String> response = result.join();
		assertEquals(200, response.getStatus());
		assertEquals(3, callCount.get());
	}

	private static void awaitCallCount(AtomicInteger callCount, int expected) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
		while (callCount.get() < expected) {
			if (System.nanoTime() > deadline) {
				fail("Timed out waiting for " + expected + " calls; observed " + callCount.get());
			}
			Thread.sleep(10);
		}
	}

}
