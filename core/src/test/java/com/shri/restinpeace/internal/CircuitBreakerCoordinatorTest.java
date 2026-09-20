package com.shri.restinpeace.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.CircuitBreakerConfig;
import com.shri.restinpeace.CircuitBreakerProvider;
import com.shri.restinpeace.exception.CircuitOpenException;

import kong.unirest.HttpResponse;

class CircuitBreakerCoordinatorTest {

	private static Supplier<HttpResponse<String>> respondingWith(int status) {
		return () -> new SyntheticHttpResponse<>(status, new kong.unirest.Headers(), "body");
	}

	private static Supplier<CompletableFuture<HttpResponse<String>>> respondingWithAsync(int status) {
		return () -> CompletableFuture.completedFuture(new SyntheticHttpResponse<>(status, new kong.unirest.Headers(), "body"));
	}

	@Test
	void unconfigured_neverRefusesAndNeverThrows() {
		CircuitBreakerCoordinator coordinator = new CircuitBreakerCoordinator((CircuitBreakerConfig) null);

		Supplier<HttpResponse<String>> wrapped = coordinator.wrapWithCircuitBreaker(respondingWith(500));

		for (int i = 0; i < 100; i++) {
			assertEquals(500, wrapped.get().getStatus());
		}
	}

	@Test
	void closed_belowMinimumCalls_neverTrips() {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder().slidingWindowSize(20).minimumNumberOfCalls(10)
				.failureRateThreshold(50).build();
		CircuitBreakerCoordinator coordinator = new CircuitBreakerCoordinator(config);
		Supplier<HttpResponse<String>> wrapped = coordinator.wrapWithCircuitBreaker(respondingWith(500));

		// 9 failures, one below minimumNumberOfCalls - must never trip yet.
		for (int i = 0; i < 9; i++) {
			assertEquals(500, wrapped.get().getStatus());
		}
	}

	@Test
	void closed_failureRateCrossesThreshold_trips() {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder().slidingWindowSize(10).minimumNumberOfCalls(10)
				.failureRateThreshold(50).build();
		CircuitBreakerCoordinator coordinator = new CircuitBreakerCoordinator(config);
		Supplier<HttpResponse<String>> failingCall = coordinator.wrapWithCircuitBreaker(respondingWith(500));

		// 10 calls, all failures (100% >= 50% threshold) - trips on the 10th.
		for (int i = 0; i < 9; i++) {
			failingCall.get();
		}
		failingCall.get();

		// Now open: the very next call must be refused without ever invoking the real call.
		boolean[] realCallInvoked = { false };
		Supplier<HttpResponse<String>> spyCall = coordinator.wrapWithCircuitBreaker(() -> {
			realCallInvoked[0] = true;
			return new SyntheticHttpResponse<>(200, new kong.unirest.Headers(), "body");
		});
		assertThrows(CircuitOpenException.class, spyCall::get);
		assertTrue(!realCallInvoked[0]);
	}

	@Test
	void closed_failureRateBelowThreshold_neverTrips() {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder().slidingWindowSize(10).minimumNumberOfCalls(10)
				.failureRateThreshold(50).build();
		CircuitBreakerCoordinator coordinator = new CircuitBreakerCoordinator(config);
		Supplier<HttpResponse<String>> failingCall = coordinator.wrapWithCircuitBreaker(respondingWith(500));
		Supplier<HttpResponse<String>> succeedingCall = coordinator.wrapWithCircuitBreaker(respondingWith(200));

		// 4 failures, 6 successes in the window - 40% < 50%, never trips.
		for (int i = 0; i < 4; i++) {
			failingCall.get();
		}
		for (int i = 0; i < 6; i++) {
			assertEquals(200, succeedingCall.get().getStatus());
		}
	}

	@Test
	void transportException_countsAsFailureAndPropagates() {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder().slidingWindowSize(3).minimumNumberOfCalls(3)
				.failureRateThreshold(50).build();
		CircuitBreakerCoordinator coordinator = new CircuitBreakerCoordinator(config);
		RuntimeException transportFailure = new RuntimeException("connection refused");
		Supplier<HttpResponse<String>> throwingCall = coordinator.wrapWithCircuitBreaker(() -> {
			throw transportFailure;
		});

		for (int i = 0; i < 3; i++) {
			RuntimeException thrown = assertThrows(RuntimeException.class, throwingCall::get);
			assertEquals(transportFailure, thrown);
		}

		// 3 transport failures out of 3 calls (100%) tripped the breaker - now open.
		Supplier<HttpResponse<String>> anyCall = coordinator.wrapWithCircuitBreaker(respondingWith(200));
		assertThrows(CircuitOpenException.class, anyCall::get);
	}

	@Test
	void open_afterCooldown_transitionsToHalfOpenAndCloses() throws InterruptedException {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder().slidingWindowSize(2).minimumNumberOfCalls(2)
				.failureRateThreshold(50).waitDurationInOpenState(Duration.ofMillis(50))
				.permittedCallsInHalfOpenState(2).build();
		CircuitBreakerCoordinator coordinator = new CircuitBreakerCoordinator(config);
		Supplier<HttpResponse<String>> failingCall = coordinator.wrapWithCircuitBreaker(respondingWith(500));
		failingCall.get();
		failingCall.get();

		// Open immediately after tripping.
		Supplier<HttpResponse<String>> succeedingCall = coordinator.wrapWithCircuitBreaker(respondingWith(200));
		assertThrows(CircuitOpenException.class, succeedingCall::get);

		Thread.sleep(100);

		// Cooldown elapsed: half-open trial calls are let through, and succeeding closes it.
		assertEquals(200, succeedingCall.get().getStatus());
		assertEquals(200, succeedingCall.get().getStatus());

		// Closed again: a fresh window, so one more success doesn't re-trip anything.
		assertEquals(200, succeedingCall.get().getStatus());
	}

	@Test
	void halfOpen_stillFailing_reopens() throws InterruptedException {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder().slidingWindowSize(2).minimumNumberOfCalls(2)
				.failureRateThreshold(50).waitDurationInOpenState(Duration.ofMillis(50))
				.permittedCallsInHalfOpenState(2).build();
		CircuitBreakerCoordinator coordinator = new CircuitBreakerCoordinator(config);
		Supplier<HttpResponse<String>> failingCall = coordinator.wrapWithCircuitBreaker(respondingWith(500));
		failingCall.get();
		failingCall.get();

		Thread.sleep(100);

		// Both half-open trial calls fail too - re-opens instead of closing.
		failingCall.get();
		failingCall.get();

		assertThrows(CircuitOpenException.class, failingCall::get);
	}

	@Test
	void timeBased_prunesEntriesOutsideWindow() throws InterruptedException {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder().slidingWindowSize(Duration.ofMillis(50))
				.minimumNumberOfCalls(3).failureRateThreshold(50).build();
		CircuitBreakerCoordinator coordinator = new CircuitBreakerCoordinator(config);
		Supplier<HttpResponse<String>> failingCall = coordinator.wrapWithCircuitBreaker(respondingWith(500));

		failingCall.get();
		failingCall.get();

		Thread.sleep(100);

		// Those two failures aged out of the 50ms window - if they hadn't, this very
		// next call would already make the window 3/3 = 100% and trip immediately.
		assertEquals(500, failingCall.get().getStatus());
		assertEquals(500, failingCall.get().getStatus());
		// Now 3 fresh, unpruned failures (100% >= 50%) - genuinely trips this time.
		assertEquals(500, failingCall.get().getStatus());
		assertThrows(CircuitOpenException.class, failingCall::get);
	}

	@Test
	void timeBasedWindow_pruningASuccessfulEntry_doesNotDecrementTheFailureCount() throws InterruptedException {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder().slidingWindowSize(Duration.ofMillis(50))
				.minimumNumberOfCalls(2).failureRateThreshold(60).build();
		CircuitBreakerCoordinator coordinator = new CircuitBreakerCoordinator(config);
		Supplier<HttpResponse<String>> succeedingCall = coordinator.wrapWithCircuitBreaker(respondingWith(200));
		Supplier<HttpResponse<String>> failingCall = coordinator.wrapWithCircuitBreaker(respondingWith(500));

		succeedingCall.get();

		Thread.sleep(100); // the success entry ages out of the 50ms window

		// The first call here prunes the (successful) entry above. If pruning ever
		// decremented the failure count regardless of what it pruned, this would
		// leave the count wrongly negative and mask the two real failures below -
		// 1/2 = 50% would read as under the 60% threshold instead of 2/2 = 100%
		// over it, and the breaker would never trip.
		failingCall.get();
		failingCall.get();

		assertThrows(CircuitOpenException.class, failingCall::get);
	}

	@Test
	void recordFailureForStatus_customPredicate_respected() {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder().slidingWindowSize(2).minimumNumberOfCalls(2)
				.failureRateThreshold(50).recordFailureForStatus(status -> status == 429).build();
		CircuitBreakerCoordinator coordinator = new CircuitBreakerCoordinator(config);

		// A 500 doesn't count as a failure under this custom predicate.
		Supplier<HttpResponse<String>> serverErrorCall = coordinator.wrapWithCircuitBreaker(respondingWith(500));
		serverErrorCall.get();
		serverErrorCall.get();
		assertEquals(500, serverErrorCall.get().getStatus());

		// A 429 does.
		CircuitBreakerCoordinator secondCoordinator = new CircuitBreakerCoordinator(config);
		Supplier<HttpResponse<String>> rateLimitedCall = secondCoordinator.wrapWithCircuitBreaker(respondingWith(429));
		rateLimitedCall.get();
		rateLimitedCall.get();
		assertThrows(CircuitOpenException.class, rateLimitedCall::get);
	}

	@Test
	void countBasedWindow_overwritingAStoredFailure_correctlyForgetsIt() {
		// minimumNumberOfCalls == slidingWindowSize, so the very first full window
		// already decides whether to trip - the only way to observe what happens on
		// a *second* pass through the ring buffer (overwriting an already-recorded
		// slot) without the breaker having already opened on the first pass.
		CircuitBreakerConfig config = CircuitBreakerConfig.builder().slidingWindowSize(3).minimumNumberOfCalls(3)
				.failureRateThreshold(50).build();
		CircuitBreakerCoordinator coordinator = new CircuitBreakerCoordinator(config);
		Supplier<HttpResponse<String>> failingCall = coordinator.wrapWithCircuitBreaker(respondingWith(500));
		Supplier<HttpResponse<String>> succeedingCall = coordinator.wrapWithCircuitBreaker(respondingWith(200));

		failingCall.get();      // slot 0 = failure
		succeedingCall.get();   // slot 1 = success
		succeedingCall.get();   // slot 2 = success; window full, 1/3 = 33% < 50%, no trip

		// Overwrites slot 0, which held a failure. If the ring buffer failed to
		// decrement its failure count on overwrite, the window would be
		// (mis)counted as 2 failures (the "forgotten" one plus this new one) out
		// of 3 = 66% >= 50% and wrongly trip; correctly forgetting it keeps the
		// count at 1/3 = 33%, unchanged.
		assertEquals(500, failingCall.get().getStatus());
	}

	@Test
	void timeBasedWindow_closingViaHalfOpen_resetsTheWindow() throws InterruptedException {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder().slidingWindowSize(Duration.ofSeconds(30))
				.minimumNumberOfCalls(2).failureRateThreshold(50).waitDurationInOpenState(Duration.ofMillis(50))
				.permittedCallsInHalfOpenState(1).build();
		CircuitBreakerCoordinator coordinator = new CircuitBreakerCoordinator(config);
		Supplier<HttpResponse<String>> failingCall = coordinator.wrapWithCircuitBreaker(respondingWith(500));
		Supplier<HttpResponse<String>> succeedingCall = coordinator.wrapWithCircuitBreaker(respondingWith(200));

		failingCall.get();
		failingCall.get(); // trips: 2/2 = 100% >= 50%

		Thread.sleep(100); // cooldown elapses

		// The half-open trial succeeds, closing the breaker.
		assertEquals(200, succeedingCall.get().getStatus());

		// The two old failures are still well within the 30s time window - if
		// closing hadn't reset it, this one fresh success would make the window
		// 2 failures / 3 calls = 66% >= 50% and immediately re-trip. A properly
		// reset window is just this one success (0%), so it doesn't.
		assertEquals(200, succeedingCall.get().getStatus());
	}

	@Test
	void halfOpen_moreConcurrentCallsThanPermits_refusesTheExcess() throws Exception {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder().slidingWindowSize(2).minimumNumberOfCalls(2)
				.failureRateThreshold(50).waitDurationInOpenState(Duration.ofMillis(50))
				.permittedCallsInHalfOpenState(2).build();
		CircuitBreakerCoordinator coordinator = new CircuitBreakerCoordinator(config);
		Supplier<HttpResponse<String>> failingCall = coordinator.wrapWithCircuitBreaker(respondingWith(500));
		failingCall.get();
		failingCall.get(); // trips

		Thread.sleep(100); // cooldown elapses

		CountDownLatch bothInFlight = new CountDownLatch(2);
		CountDownLatch releaseTrials = new CountDownLatch(1);
		Supplier<HttpResponse<String>> blockingTrial = coordinator.wrapWithCircuitBreaker(() -> {
			bothInFlight.countDown();
			try {
				assertTrue(releaseTrials.await(5, TimeUnit.SECONDS));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return new SyntheticHttpResponse<>(200, new kong.unirest.Headers(), "body");
		});

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<?> trial1 = pool.submit(blockingTrial::get);
			Future<?> trial2 = pool.submit(blockingTrial::get);
			assertTrue(bothInFlight.await(5, TimeUnit.SECONDS));

			// Both permitted half-open slots are held by the two in-flight trials
			// above; a third concurrent attempt must be refused outright instead of
			// waiting or being let through.
			assertThrows(CircuitOpenException.class, () -> coordinator.wrapWithCircuitBreaker(respondingWith(200)).get());

			releaseTrials.countDown();
			trial1.get(5, TimeUnit.SECONDS);
			trial2.get(5, TimeUnit.SECONDS);
		} finally {
			pool.shutdown();
		}
	}

	@Test
	void async_unconfigured_neverRefusesAndNeverThrows() throws Exception {
		CircuitBreakerCoordinator coordinator = new CircuitBreakerCoordinator((CircuitBreakerConfig) null);

		Supplier<CompletableFuture<HttpResponse<String>>> wrapped = coordinator
				.wrapWithCircuitBreakerAsync(respondingWithAsync(500));

		for (int i = 0; i < 20; i++) {
			assertEquals(500, wrapped.get().get(5, TimeUnit.SECONDS).getStatus());
		}
	}

	@Test
	void async_failureRateCrossesThreshold_recordsOutcomesAndTrips() throws Exception {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder().slidingWindowSize(4).minimumNumberOfCalls(4)
				.failureRateThreshold(50).build();
		CircuitBreakerCoordinator coordinator = new CircuitBreakerCoordinator(config);
		Supplier<CompletableFuture<HttpResponse<String>>> failingCall = coordinator
				.wrapWithCircuitBreakerAsync(respondingWithAsync(500));

		// 4 real failures, 100% >= 50% - trips on the 4th, exactly mirroring the
		// sync test's proof that async outcomes are recorded into the same window.
		for (int i = 0; i < 4; i++) {
			assertEquals(500, failingCall.get().get(5, TimeUnit.SECONDS).getStatus());
		}

		Supplier<CompletableFuture<HttpResponse<String>>> succeedingCall = coordinator
				.wrapWithCircuitBreakerAsync(respondingWithAsync(200));
		CompletableFuture<HttpResponse<String>> refused = succeedingCall.get();
		ExecutionException thrown = assertThrows(ExecutionException.class, () -> refused.get(5, TimeUnit.SECONDS));
		assertTrue(thrown.getCause() instanceof CircuitOpenException);
	}

	@Test
	void async_closed_recordsSuccessOutcome_soAHealthyResponseNeverContributesToTheFailureRate() throws Exception {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder().slidingWindowSize(2).minimumNumberOfCalls(2)
				.failureRateThreshold(50).build();
		CircuitBreakerCoordinator coordinator = new CircuitBreakerCoordinator(config);
		Supplier<CompletableFuture<HttpResponse<String>>> succeedingCall = coordinator
				.wrapWithCircuitBreakerAsync(respondingWithAsync(200));

		// Both calls succeed (200) - if a healthy response were ever wrongly
		// recorded as a failure, 2/2 = 100% >= 50% would trip the breaker on the
		// second call; it must not.
		assertEquals(200, succeedingCall.get().get(5, TimeUnit.SECONDS).getStatus());
		assertEquals(200, succeedingCall.get().get(5, TimeUnit.SECONDS).getStatus());
	}

	@Test
	void async_transportFailure_countsAsFailureAndPropagates() throws Exception {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder().slidingWindowSize(3).minimumNumberOfCalls(3)
				.failureRateThreshold(50).build();
		CircuitBreakerCoordinator coordinator = new CircuitBreakerCoordinator(config);
		RuntimeException transportFailure = new RuntimeException("connection refused");
		Supplier<CompletableFuture<HttpResponse<String>>> throwingCall = coordinator.wrapWithCircuitBreakerAsync(() -> {
			CompletableFuture<HttpResponse<String>> failed = new CompletableFuture<>();
			failed.completeExceptionally(transportFailure);
			return failed;
		});

		for (int i = 0; i < 3; i++) {
			CompletableFuture<HttpResponse<String>> result = throwingCall.get();
			ExecutionException thrown = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
			assertEquals(transportFailure, thrown.getCause());
		}

		// 3 transport failures out of 3 calls (100%) tripped the breaker - now open.
		CompletableFuture<HttpResponse<String>> refused = coordinator.wrapWithCircuitBreakerAsync(respondingWithAsync(200))
				.get();
		ExecutionException thrown = assertThrows(ExecutionException.class, () -> refused.get(5, TimeUnit.SECONDS));
		assertTrue(thrown.getCause() instanceof CircuitOpenException);
	}

	@Test
	void async_open_returnsAlreadyFailedFuture_withoutThrowingSynchronously() throws Exception {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder().slidingWindowSize(1).minimumNumberOfCalls(1)
				.failureRateThreshold(50).build();
		CircuitBreakerCoordinator coordinator = new CircuitBreakerCoordinator(config);
		coordinator.wrapWithCircuitBreakerAsync(respondingWithAsync(500)).get().get(5, TimeUnit.SECONDS); // trips

		boolean[] realCallInvoked = { false };
		Supplier<CompletableFuture<HttpResponse<String>>> spyCall = coordinator.wrapWithCircuitBreakerAsync(() -> {
			realCallInvoked[0] = true;
			return CompletableFuture.completedFuture(new SyntheticHttpResponse<>(200, new kong.unirest.Headers(), "body"));
		});

		// The whole point of the async wrap: calling .get() on the returned
		// Supplier itself must never throw - the failure must arrive via the
		// future it returns, since a synchronous throw here would be lost if this
		// supplier is ever invoked from inside a scheduled retry callback (see the
		// coordinator's own javadoc for wrapWithCircuitBreakerAsync).
		CompletableFuture<HttpResponse<String>> result = spyCall.get();
		ExecutionException thrown = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
		assertTrue(thrown.getCause() instanceof CircuitOpenException);
		assertTrue(!realCallInvoked[0]);
	}

	private static final class RecordingProvider implements CircuitBreakerProvider {
		boolean permit = true;
		int successCalls;
		int errorCalls;
		Integer lastStatusCode;
		Throwable lastError;

		public boolean tryAcquirePermission() {
			return permit;
		}

		public void onSuccess(long durationNanos, int statusCode) {
			successCalls++;
			lastStatusCode = statusCode;
		}

		public void onError(long durationNanos, Throwable t) {
			errorCalls++;
			lastError = t;
		}
	}

	@Test
	void provider_permissionGranted_invokesRealCallAndReportsSuccess() {
		RecordingProvider provider = new RecordingProvider();
		CircuitBreakerCoordinator coordinator = new CircuitBreakerCoordinator((CircuitBreakerProvider) provider);
		Supplier<HttpResponse<String>> wrapped = coordinator.wrapWithCircuitBreaker(respondingWith(200));

		assertEquals(200, wrapped.get().getStatus());
		assertEquals(1, provider.successCalls);
		assertEquals(0, provider.errorCalls);
		assertEquals(200, provider.lastStatusCode);
	}

	@Test
	void provider_permissionDenied_refusesWithoutInvokingRealCall() {
		RecordingProvider provider = new RecordingProvider();
		provider.permit = false;
		CircuitBreakerCoordinator coordinator = new CircuitBreakerCoordinator((CircuitBreakerProvider) provider);
		boolean[] realCallInvoked = { false };
		Supplier<HttpResponse<String>> wrapped = coordinator.wrapWithCircuitBreaker(() -> {
			realCallInvoked[0] = true;
			return new SyntheticHttpResponse<>(200, new kong.unirest.Headers(), "body");
		});

		assertThrows(CircuitOpenException.class, wrapped::get);
		assertTrue(!realCallInvoked[0]);
		assertEquals(0, provider.successCalls);
		assertEquals(0, provider.errorCalls);
	}

	@Test
	void provider_transportFailure_reportsOnError() {
		RecordingProvider provider = new RecordingProvider();
		CircuitBreakerCoordinator coordinator = new CircuitBreakerCoordinator((CircuitBreakerProvider) provider);
		RuntimeException transportFailure = new RuntimeException("connection refused");
		Supplier<HttpResponse<String>> throwingCall = coordinator.wrapWithCircuitBreaker(() -> {
			throw transportFailure;
		});

		RuntimeException thrown = assertThrows(RuntimeException.class, throwingCall::get);
		assertEquals(transportFailure, thrown);
		assertEquals(0, provider.successCalls);
		assertEquals(1, provider.errorCalls);
		assertEquals(transportFailure, provider.lastError);
	}

	@Test
	void async_provider_permissionGranted_invokesRealCallAndReportsSuccess() throws Exception {
		RecordingProvider provider = new RecordingProvider();
		CircuitBreakerCoordinator coordinator = new CircuitBreakerCoordinator((CircuitBreakerProvider) provider);
		Supplier<CompletableFuture<HttpResponse<String>>> wrapped = coordinator
				.wrapWithCircuitBreakerAsync(respondingWithAsync(200));

		assertEquals(200, wrapped.get().get(5, TimeUnit.SECONDS).getStatus());
		assertEquals(1, provider.successCalls);
		assertEquals(200, provider.lastStatusCode);
	}

	@Test
	void async_provider_permissionDenied_returnsAlreadyFailedFuture_withoutThrowingSynchronously() throws Exception {
		RecordingProvider provider = new RecordingProvider();
		provider.permit = false;
		CircuitBreakerCoordinator coordinator = new CircuitBreakerCoordinator((CircuitBreakerProvider) provider);
		boolean[] realCallInvoked = { false };
		Supplier<CompletableFuture<HttpResponse<String>>> wrapped = coordinator.wrapWithCircuitBreakerAsync(() -> {
			realCallInvoked[0] = true;
			return CompletableFuture
					.completedFuture(new SyntheticHttpResponse<>(200, new kong.unirest.Headers(), "body"));
		});

		CompletableFuture<HttpResponse<String>> result = wrapped.get();
		ExecutionException thrown = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
		assertTrue(thrown.getCause() instanceof CircuitOpenException);
		assertTrue(!realCallInvoked[0]);
	}

	@Test
	void async_provider_transportFailure_reportsOnError() throws Exception {
		RecordingProvider provider = new RecordingProvider();
		CircuitBreakerCoordinator coordinator = new CircuitBreakerCoordinator((CircuitBreakerProvider) provider);
		RuntimeException transportFailure = new RuntimeException("connection refused");
		Supplier<CompletableFuture<HttpResponse<String>>> throwingCall = coordinator.wrapWithCircuitBreakerAsync(() -> {
			CompletableFuture<HttpResponse<String>> failed = new CompletableFuture<>();
			failed.completeExceptionally(transportFailure);
			return failed;
		});

		CompletableFuture<HttpResponse<String>> result = throwingCall.get();
		ExecutionException thrown = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
		assertEquals(transportFailure, thrown.getCause());
		assertEquals(1, provider.errorCalls);
		assertEquals(transportFailure, provider.lastError);
	}

}
