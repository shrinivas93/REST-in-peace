package com.shri.restinpeace.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
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

}
