package com.shri.restinpeace.exception;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@code isClientError}/{@code isServerError}/{@code is} - the status-range helpers, in isolation. */
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

}
