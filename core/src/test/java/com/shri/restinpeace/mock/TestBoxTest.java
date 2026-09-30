package com.shri.restinpeace.mock;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Regression test for {@link TestBox#failed(RuntimeException)}'s null-check:
 * before it existed, {@code TestBox.failed(null)} silently built a box whose
 * {@code isFailed()} reads {@code false} and {@code get()} returns
 * {@code null} - the exact failure signal this factory method exists to
 * carry, lost without a trace.
 */
class TestBoxTest {

	@Test
	void failed_withNullErrorThrowsInstead() {
		assertThrows(NullPointerException.class, () -> TestBox.failed(null));
	}

}
