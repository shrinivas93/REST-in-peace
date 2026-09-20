package com.shri.restinpeace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class BulkheadConfigTest {

	@Test
	void builder_defaults() {
		BulkheadConfig config = BulkheadConfig.builder().build();

		assertEquals(25, config.getMaxConcurrentCalls());
		assertEquals(0L, config.getMaxWaitDurationMillis());
	}

	@Test
	void maxConcurrentCalls_and_maxWaitDuration_areApplied() {
		BulkheadConfig config = BulkheadConfig.builder().maxConcurrentCalls(5)
				.maxWaitDuration(Duration.ofMillis(250)).build();

		assertEquals(5, config.getMaxConcurrentCalls());
		assertEquals(250L, config.getMaxWaitDurationMillis());
	}

	@Test
	void maxWaitDuration_zero_isAllowed() {
		BulkheadConfig config = BulkheadConfig.builder().maxWaitDuration(Duration.ZERO).build();

		assertEquals(0L, config.getMaxWaitDurationMillis());
	}

	@Test
	void invalidArguments_throw() {
		assertThrows(IllegalArgumentException.class, () -> BulkheadConfig.builder().maxConcurrentCalls(0));
		assertThrows(IllegalArgumentException.class, () -> BulkheadConfig.builder().maxConcurrentCalls(-1));
		assertThrows(IllegalArgumentException.class, () -> BulkheadConfig.builder().maxWaitDuration(null));
		assertThrows(IllegalArgumentException.class,
				() -> BulkheadConfig.builder().maxWaitDuration(Duration.ofMillis(-1)));
	}

}
