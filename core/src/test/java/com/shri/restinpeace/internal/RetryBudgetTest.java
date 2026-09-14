package com.shri.restinpeace.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RetryBudgetTest {

	@Test
	void tryConsume_upToCapacity_succeeds() {
		RetryBudget budget = new RetryBudget(3, 60_000);

		assertTrue(budget.tryConsume());
		assertTrue(budget.tryConsume());
		assertTrue(budget.tryConsume());
	}

	@Test
	void tryConsume_pastCapacity_fails() {
		RetryBudget budget = new RetryBudget(2, 60_000);

		assertTrue(budget.tryConsume());
		assertTrue(budget.tryConsume());
		assertFalse(budget.tryConsume());
	}

	@Test
	void tryConsume_refillsOverTime() throws InterruptedException {
		RetryBudget budget = new RetryBudget(1, 50);

		assertTrue(budget.tryConsume());
		assertFalse(budget.tryConsume());

		Thread.sleep(100);

		assertTrue(budget.tryConsume());
	}

	@Test
	void constructor_nonPositiveArguments_throws() {
		assertThrows(IllegalArgumentException.class, () -> new RetryBudget(0, 60_000));
		assertThrows(IllegalArgumentException.class, () -> new RetryBudget(-1, 60_000));
		assertThrows(IllegalArgumentException.class, () -> new RetryBudget(5, 0));
		assertThrows(IllegalArgumentException.class, () -> new RetryBudget(5, -1));
	}

}
