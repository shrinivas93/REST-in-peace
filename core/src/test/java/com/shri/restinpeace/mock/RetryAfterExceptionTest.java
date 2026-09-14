package com.shri.restinpeace.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shri.restinpeace.RIP;
import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.exception.RestInPeaceHttpException;

/**
 * {@link RestInPeaceHttpException#getRetryAfterMillis()} - the same
 * {@code Retry-After} parsing {@code @Retry} uses internally, surfaced on
 * the exception for a method with no {@code @Retry} at all.
 */
class RetryAfterExceptionTest {

	@RestClient
	interface NoRetryApi {
		@GET("/limited")
		String get();
	}

	private MockRestServer server;
	private NoRetryApi api;

	@BeforeEach
	void setUp() {
		server = MockRestServer.start();
		api = RIP.getClient(NoRetryApi.class, server.baseUrl());
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	@Test
	void deltaSecondsRetryAfter_isParsedToMillis() {
		server.on(HTTPMethod.GET, "/limited", MockResponse.status(429, "").header("Retry-After", "2"));

		RestInPeaceHttpException exception = assertThrows(RestInPeaceHttpException.class, api::get);

		assertEquals(2_000L, exception.getRetryAfterMillis());
	}

	@Test
	void noRetryAfterHeader_isNull() {
		server.on(HTTPMethod.GET, "/limited", MockResponse.status(503, ""));

		RestInPeaceHttpException exception = assertThrows(RestInPeaceHttpException.class, api::get);

		assertNull(exception.getRetryAfterMillis());
	}

}
