package com.shri.restinpeace.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@code isClientError}/{@code isServerError}/{@code isRedirect}/{@code is}/
 * {@code getRetryAfterMillis} - the status-range helpers and the parsed
 * {@code Retry-After} accessor, in isolation.
 */
class RestInPeaceHttpExceptionTest {

	@Test
	void isClientError_trueOnlyFor4xx() {
		assertTrue(new RestInPeaceHttpException(400, "", null).isClientError());
		assertTrue(new RestInPeaceHttpException(404, "", null).isClientError());
		assertTrue(new RestInPeaceHttpException(499, "", null).isClientError());
		assertFalse(new RestInPeaceHttpException(399, "", null).isClientError());
		assertFalse(new RestInPeaceHttpException(500, "", null).isClientError());
	}

	@Test
	void isServerError_trueOnlyFor5xx() {
		assertTrue(new RestInPeaceHttpException(500, "", null).isServerError());
		assertTrue(new RestInPeaceHttpException(503, "", null).isServerError());
		assertTrue(new RestInPeaceHttpException(599, "", null).isServerError());
		assertFalse(new RestInPeaceHttpException(499, "", null).isServerError());
		assertFalse(new RestInPeaceHttpException(600, "", null).isServerError());
	}

	@Test
	void is_matchesExactStatusOnly() {
		RestInPeaceHttpException exception = new RestInPeaceHttpException(404, "", null);

		assertTrue(exception.is(404));
		assertFalse(exception.is(400));
	}

	@Test
	void isRedirect_trueOnlyFor3xx() {
		assertTrue(new RestInPeaceHttpException(300, "", null).isRedirect());
		assertTrue(new RestInPeaceHttpException(301, "", null).isRedirect());
		assertTrue(new RestInPeaceHttpException(399, "", null).isRedirect());
		assertFalse(new RestInPeaceHttpException(299, "", null).isRedirect());
		assertFalse(new RestInPeaceHttpException(400, "", null).isRedirect());
	}

	@Test
	void getRetryAfterMillis_threeArgConstructor_isAlwaysNull() {
		RestInPeaceHttpException exception = new RestInPeaceHttpException(503, "", null);

		assertNull(exception.getRetryAfterMillis());
	}

	@Test
	void getRetryAfterMillis_fourArgConstructor_returnsTheGivenValue() {
		RestInPeaceHttpException exception = new RestInPeaceHttpException(429, "", null, 2_000L);

		assertEquals(2_000L, exception.getRetryAfterMillis());
	}

	@Test
	void getRetryAfterMillis_fourArgConstructor_withNull_isNull() {
		RestInPeaceHttpException exception = new RestInPeaceHttpException(429, "", null, null);

		assertNull(exception.getRetryAfterMillis());
	}

}
