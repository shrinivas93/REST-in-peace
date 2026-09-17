package com.shri.restinpeace.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import kong.unirest.HttpResponse;

/**
 * {@link SyntheticHttpResponse}'s {@code HttpResponse<T>} interface-
 * completeness methods - per the class's own javadoc, only
 * {@code getStatus()}/{@code getBody()}/{@code getHeaders()} are ever
 * actually exercised by the interceptor short-circuit/cache machinery that
 * builds one, so every other method here needs a direct unit test instead of
 * relying on an end-to-end short-circuit/caching scenario to reach it.
 */
class SyntheticHttpResponseTest {

	@Test
	void getStatusText_isAlwaysEmpty() {
		SyntheticHttpResponse<String> response = new SyntheticHttpResponse<>(200, new kong.unirest.Headers(), "body");

		assertEquals("", response.getStatusText());
	}

	@Test
	void getParsingError_isAlwaysEmpty() {
		SyntheticHttpResponse<String> response = new SyntheticHttpResponse<>(200, new kong.unirest.Headers(), "body");

		assertFalse(response.getParsingError().isPresent());
	}

	@Test
	void mapBody_appliesTheFunctionToTheBody() {
		SyntheticHttpResponse<String> response = new SyntheticHttpResponse<>(200, new kong.unirest.Headers(), "body");

		assertEquals(4, (int) response.mapBody(String::length));
	}

	@Test
	void map_returnsANewResponseWithTheMappedBody() {
		SyntheticHttpResponse<String> response = new SyntheticHttpResponse<>(200, new kong.unirest.Headers(), "body");

		HttpResponse<Integer> mapped = response.map(String::length);

		assertEquals(4, mapped.getBody());
		assertEquals(200, mapped.getStatus());
	}

	@Test
	void ifSuccess_successStatus_invokesTheConsumer() {
		SyntheticHttpResponse<String> response = new SyntheticHttpResponse<>(200, new kong.unirest.Headers(), "body");
		boolean[] invoked = { false };

		HttpResponse<String> result = response.ifSuccess(r -> invoked[0] = true);

		assertTrue(invoked[0]);
		assertSame(response, result);
	}

	@Test
	void ifSuccess_failureStatus_neverInvokesTheConsumer() {
		SyntheticHttpResponse<String> response = new SyntheticHttpResponse<>(404, new kong.unirest.Headers(), "body");
		boolean[] invoked = { false };

		response.ifSuccess(r -> invoked[0] = true);

		assertFalse(invoked[0]);
	}

	@Test
	void ifFailure_failureStatus_invokesTheConsumer() {
		SyntheticHttpResponse<String> response = new SyntheticHttpResponse<>(404, new kong.unirest.Headers(), "body");
		boolean[] invoked = { false };

		HttpResponse<String> result = response.ifFailure(r -> invoked[0] = true);

		assertTrue(invoked[0]);
		assertSame(response, result);
	}

	@Test
	void ifFailure_successStatus_neverInvokesTheConsumer() {
		SyntheticHttpResponse<String> response = new SyntheticHttpResponse<>(200, new kong.unirest.Headers(), "body");
		boolean[] invoked = { false };

		response.ifFailure(r -> invoked[0] = true);

		assertFalse(invoked[0]);
	}

	@Test
	void ifFailureWithType_neverInvokesTheConsumerAndReturnsThis() {
		SyntheticHttpResponse<String> response = new SyntheticHttpResponse<>(404, new kong.unirest.Headers(), "body");
		boolean[] invoked = { false };

		HttpResponse<String> result = response.ifFailure(RuntimeException.class, e -> invoked[0] = true);

		assertFalse(invoked[0]);
		assertSame(response, result);
	}

	@Test
	void isSuccess_reflectsTheStatusCode() {
		assertTrue(new SyntheticHttpResponse<>(200, new kong.unirest.Headers(), "body").isSuccess());
		assertFalse(new SyntheticHttpResponse<>(404, new kong.unirest.Headers(), "body").isSuccess());
	}

	@Test
	void mapError_isAlwaysNull() {
		SyntheticHttpResponse<String> response = new SyntheticHttpResponse<>(404, new kong.unirest.Headers(), "body");

		assertNull(response.mapError(RuntimeException.class));
	}

	@Test
	void getCookies_isAlwaysEmpty() {
		SyntheticHttpResponse<String> response = new SyntheticHttpResponse<>(200, new kong.unirest.Headers(), "body");

		assertTrue(response.getCookies().isEmpty());
	}

	@Test
	void getRequestSummary_returnsAFixedStubSummary() {
		SyntheticHttpResponse<String> response = new SyntheticHttpResponse<>(200, new kong.unirest.Headers(), "body");

		kong.unirest.HttpRequestSummary summary = response.getRequestSummary();

		assertEquals(kong.unirest.HttpMethod.GET, summary.getHttpMethod());
		assertEquals("", summary.getUrl());
		assertEquals("", summary.getRawPath());
		assertEquals("GET", summary.asString());
	}

}
