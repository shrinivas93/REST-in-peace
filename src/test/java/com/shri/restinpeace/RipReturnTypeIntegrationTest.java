package com.shri.restinpeace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.exception.RestInPeaceHttpException;

/**
 * Return-type decoding - {@code void}/POJO/{@code RipResponse<T>} bodies,
 * and turning a non-2xx status into a thrown
 * {@link RestInPeaceHttpException} (typed via {@code @ErrorType} or raw) -
 * split out of {@code RipIntegrationTest} (see {@link AbstractRipIntegrationTest}).
 */
class RipReturnTypeIntegrationTest extends AbstractRipIntegrationTest {

	@Test
	void get_withPojoReturnType_deserializesJsonResponse() {
		LocalApi api = RIP.getClient(LocalApi.class);

		Payload payload = api.getPayload(port, "abc");

		assertEquals("Shrinivas", payload.name);
		assertEquals(1993, payload.age);
	}

	@Test
	void get_withVoidReturnType_doesNotThrow() {
		LocalApi api = RIP.getClient(LocalApi.class);

		api.ping(port, "abc");

		assertEquals("GET", LAST_REQUEST.get().method);
	}

	@Test
	void get_withRipResponseOfPojo_exposesStatusHeadersAndDeserializedBody() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RipResponse<Payload> response = api.getPayloadWithResponse(port, "abc");

		assertEquals(200, response.getStatus());
		assertEquals("application/json", response.getHeader("Content-Type"));
		assertEquals("application/json", response.getHeader("content-type"));
		assertEquals("Shrinivas", response.getBody().name);
		assertEquals(1993, response.getBody().age);
	}

	@Test
	void get_withRipResponseOfString_exposesRawBody() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RipResponse<String> response = api.getWithResponseString(port, "abc");

		assertEquals(200, response.getStatus());
		assertEquals("ok", response.getBody());
	}

	@Test
	void get_withRipResponseOfVoid_hasNullBodyButRealStatus() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RipResponse<Void> response = api.getWithResponseVoid(port, "abc");

		assertEquals(200, response.getStatus());
		assertNull(response.getBody());
	}

	@Test
	void get_withRipResponseAndRetry_succeedsAfterRetryingAndStillExposesHeaders() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RipResponse<String> response = api.getWithResponseAndRetry(port, "abc");

		assertEquals(200, response.getStatus());
		assertEquals("ok", response.getBody());
	}

	@Test
	void get_withRipResponseOnNonSuccessStatus_stillThrowsInsteadOfWrapping() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RestInPeaceHttpException exception = assertThrows(RestInPeaceHttpException.class,
				() -> api.getErrorWithResponse(port, "abc"));
		assertEquals(422, exception.getStatus());
	}

	@Test
	void getAsync_withRipResponseOfPojo_completesWithStatusHeadersAndBody()
			throws InterruptedException, ExecutionException, TimeoutException {
		LocalApi api = RIP.getClient(LocalApi.class);

		RipResponse<Payload> response = api.getPayloadWithResponseAsync(port, "abc").get(5, TimeUnit.SECONDS);

		assertEquals(200, response.getStatus());
		assertEquals("application/json", response.getHeader("Content-Type"));
		assertEquals("Shrinivas", response.getBody().name);
	}

	@Test
	void get_withNonSuccessStatusAndNoErrorType_throwsWithRawBody() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RestInPeaceHttpException exception = assertThrows(RestInPeaceHttpException.class,
				() -> api.getWithUntypedError(port, "x"));

		assertEquals(422, exception.getStatus());
		assertEquals("{\"code\":\"INVALID\",\"message\":\"nope\"}", exception.getRawBody());
		assertEquals(exception.getRawBody(), exception.getErrorBody());
	}

	@Test
	void get_withNonSuccessStatusAndErrorType_throwsWithDeserializedErrorBody() {
		LocalApi api = RIP.getClient(LocalApi.class);

		RestInPeaceHttpException exception = assertThrows(RestInPeaceHttpException.class,
				() -> api.getWithTypedError(port, "x"));

		assertEquals(422, exception.getStatus());
		ApiError error = exception.getErrorBody();
		assertEquals("INVALID", error.code);
		assertEquals("nope", error.message);
	}

	@Test
	void getAsync_withNonSuccessStatusAndErrorType_completesExceptionally() {
		LocalApi api = RIP.getClient(LocalApi.class);

		CompletableFuture<String> future = api.getWithTypedErrorAsync(port, "x");

		ExecutionException executionException = assertThrows(ExecutionException.class,
				() -> future.get(5, TimeUnit.SECONDS));
		assertTrue(executionException.getCause() instanceof RestInPeaceHttpException);
		RestInPeaceHttpException httpException = (RestInPeaceHttpException) executionException.getCause();
		assertEquals(422, httpException.getStatus());
		ApiError error = httpException.getErrorBody();
		assertEquals("INVALID", error.code);
	}

	@Test
	void get_withVoidReturnTypeAndNonSuccessStatus_stillThrows() {
		LocalApi api = RIP.getClient(LocalApi.class);

		assertThrows(RestInPeaceHttpException.class, () -> api.pingAlwaysFailing(port, "x"));
	}

}
