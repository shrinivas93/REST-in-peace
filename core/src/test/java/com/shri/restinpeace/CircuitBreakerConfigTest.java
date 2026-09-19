package com.shri.restinpeace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.CircuitBreakerConfig.SlidingWindowType;

class CircuitBreakerConfigTest {

	@Test
	void builder_defaults() {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder().build();

		assertEquals(SlidingWindowType.COUNT_BASED, config.getSlidingWindowType());
		assertEquals(20, config.getSlidingWindowSize());
		assertEquals(10, config.getMinimumNumberOfCalls());
		assertEquals(50, config.getFailureRateThreshold());
		assertEquals(30_000L, config.getWaitDurationInOpenStateMillis());
		assertEquals(3, config.getPermittedCallsInHalfOpenState());
		assertEquals(false, config.getRecordFailureForStatus().test(404));
		assertEquals(true, config.getRecordFailureForStatus().test(500));
	}

	@Test
	void slidingWindowSize_duration_selectsTimeBased() {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder().slidingWindowSize(Duration.ofSeconds(45)).build();

		assertEquals(SlidingWindowType.TIME_BASED, config.getSlidingWindowType());
		assertEquals(45_000L, config.getSlidingWindowDurationMillis());
	}

	@Test
	void slidingWindowSize_int_selectsCountBased() {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder().slidingWindowSize(Duration.ofSeconds(45))
				.slidingWindowSize(50).build();

		assertEquals(SlidingWindowType.COUNT_BASED, config.getSlidingWindowType());
		assertEquals(50, config.getSlidingWindowSize());
	}

	@Test
	void recordFailureForStatus_customPredicate() {
		CircuitBreakerConfig config = CircuitBreakerConfig.builder().recordFailureForStatus(status -> status == 429)
				.build();

		assertEquals(true, config.getRecordFailureForStatus().test(429));
		assertEquals(false, config.getRecordFailureForStatus().test(500));
	}

	@Test
	void invalidArguments_throw() {
		assertThrows(IllegalArgumentException.class, () -> CircuitBreakerConfig.builder().slidingWindowSize(0));
		assertThrows(IllegalArgumentException.class,
				() -> CircuitBreakerConfig.builder().slidingWindowSize(Duration.ZERO));
		assertThrows(IllegalArgumentException.class,
				() -> CircuitBreakerConfig.builder().slidingWindowSize(Duration.ofSeconds(-1)));
		assertThrows(IllegalArgumentException.class, () -> CircuitBreakerConfig.builder().minimumNumberOfCalls(0));
		assertThrows(IllegalArgumentException.class, () -> CircuitBreakerConfig.builder().failureRateThreshold(0));
		assertThrows(IllegalArgumentException.class, () -> CircuitBreakerConfig.builder().failureRateThreshold(101));
		assertThrows(IllegalArgumentException.class,
				() -> CircuitBreakerConfig.builder().waitDurationInOpenState(Duration.ZERO));
		assertThrows(IllegalArgumentException.class,
				() -> CircuitBreakerConfig.builder().permittedCallsInHalfOpenState(0));
		assertThrows(IllegalArgumentException.class, () -> CircuitBreakerConfig.builder().recordFailureForStatus(null));
	}

}
