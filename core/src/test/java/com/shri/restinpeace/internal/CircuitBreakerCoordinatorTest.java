package com.shri.restinpeace.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.CircuitBreakerConfig;
import com.shri.restinpeace.exception.CircuitOpenException;

import kong.unirest.HttpResponse;

class CircuitBreakerCoordinatorTest {

	private static Supplier<HttpResponse<String>> respondingWith(int status) {
		return () -> new SyntheticHttpResponse<>(status, new kong.unirest.Headers(), "body");
	}

	@Test
	void unconfigured_neverRefusesAndNeverThrows() {
		CircuitBreakerCoordinator coordinator = new CircuitBreakerCoordinator(null);

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

}
