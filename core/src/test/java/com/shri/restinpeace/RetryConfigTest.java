package com.shri.restinpeace;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RetryConfigTest {

	@Test
	void builder_defaults_matchTheRetryAnnotationsOwnDefaults() {
		RetryConfig config = RetryConfig.builder().build();

		assertEquals(3, config.getTimes());
		assertEquals(200L, config.getDelayMillis());
		assertEquals(2.0, config.getBackoffMultiplier());
		assertEquals(0.0, config.getJitterFactor());
		assertArrayEquals(new int[] { 429, 502, 503, 504 }, config.getRetryOnStatus());
		assertTrue(!config.isIdempotent());
	}

	@Test
	void times_lessThanOne_throws() {
		assertThrows(IllegalArgumentException.class, () -> RetryConfig.builder().times(0));
		assertThrows(IllegalArgumentException.class, () -> RetryConfig.builder().times(-1));
	}

	@Test
	void backoffMultiplier_setsTheValue() {
		RetryConfig config = RetryConfig.builder().backoffMultiplier(3.0).build();

		assertEquals(3.0, config.getBackoffMultiplier());
	}

	@Test
	void jitterFactor_outOfRange_throws() {
		assertThrows(IllegalArgumentException.class, () -> RetryConfig.builder().jitterFactor(-0.1));
		assertThrows(IllegalArgumentException.class, () -> RetryConfig.builder().jitterFactor(1.1));
	}

	@Test
	void jitterFactor_inRange_setsTheValue() {
		RetryConfig config = RetryConfig.builder().jitterFactor(0.5).build();

		assertEquals(0.5, config.getJitterFactor());
	}

	@Test
	void idempotent_setsTheValue() {
		RetryConfig config = RetryConfig.builder().idempotent(true).build();

		assertTrue(config.isIdempotent());
	}

}
