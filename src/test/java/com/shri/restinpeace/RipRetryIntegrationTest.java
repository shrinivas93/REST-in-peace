package com.shri.restinpeace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import com.shri.restinpeace.exception.RestInPeaceHttpException;
import com.shri.restinpeace.interceptor.RequestContext;
import com.shri.restinpeace.interceptor.RequestInterceptor;

/**
 * The {@code @Retry} sync/async loop - transient vs. permanent failures,
 * the idempotency-key header, and a method with no {@code @Retry} at all -
 * split out of {@code RipIntegrationTest} (see {@link AbstractRipIntegrationTest}).
 */
class RipRetryIntegrationTest extends AbstractRipIntegrationTest {

	@Test
	void retry_withTransientFailure_succeedsAfterRetrying() {
		List<Integer> statuses = new ArrayList<>();
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public void afterResponse(RequestContext context, int status, Object body) {
				statuses.add(status);
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.getFlaky(port, "x");

		assertEquals("ok", result);
		assertEquals(Arrays.asList(503, 503, 200), statuses);
	}

	@Test
	void retry_idempotent_sendsAnIdenticalIdempotencyKeyAcrossEveryAttempt() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.getFlakyIdempotent(port, "x");

		assertEquals("ok", result);
		assertEquals(3, IDEMPOTENCY_KEYS_SEEN.size());
		assertNotNull(IDEMPOTENCY_KEYS_SEEN.get(0));
		assertEquals(IDEMPOTENCY_KEYS_SEEN.get(0), IDEMPOTENCY_KEYS_SEEN.get(1));
		assertEquals(IDEMPOTENCY_KEYS_SEEN.get(0), IDEMPOTENCY_KEYS_SEEN.get(2));
	}

	@Test
	void retry_notIdempotent_sendsNoIdempotencyKeyAtAll() {
		LocalApi api = RIP.getClient(LocalApi.class);

		api.getFlaky(port, "x");

		for (String key : IDEMPOTENCY_KEYS_SEEN) {
			assertNull(key);
		}
	}

	@Test
	void retry_withPermanentFailure_stopsAfterConfiguredAttemptsAndThrows() {
		List<Integer> statuses = new ArrayList<>();
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public void afterResponse(RequestContext context, int status, Object body) {
				statuses.add(status);
			}
		});
		LocalApi api = RIP.getClient(LocalApi.class);

		RestInPeaceHttpException exception = assertThrows(RestInPeaceHttpException.class,
				() -> api.getAlwaysFailingWithRetry(port, "x"));

		assertEquals(503, exception.getStatus());
		assertEquals(Arrays.asList(503, 503, 503), statuses);
		assertEquals(3, ALWAYS_FAILING_ATTEMPTS.get());
	}

	@Test
	void withoutRetryAnnotation_doesNotRetryOnFailure() {
		LocalApi api = RIP.getClient(LocalApi.class);

		assertThrows(RestInPeaceHttpException.class, () -> api.getAlwaysFailingWithoutRetry(port, "x"));

		assertEquals(1, ALWAYS_FAILING_ATTEMPTS.get());
	}

	@Test
	void retry_onTransportFailure_retriesAndEventuallyThrows() {
		LocalApi api = RIP.getClient(LocalApi.class);

		assertThrows(RuntimeException.class, api::getUnreachableWithRetry);
	}

	@Test
	void retry_withCompletableFuture_succeedsAfterRetryingWithoutBlocking()
			throws InterruptedException, ExecutionException, TimeoutException {
		LocalApi api = RIP.getClient(LocalApi.class);

		CompletableFuture<String> future = api.getFlakyAsync(port, "y");

		assertEquals("ok", future.get(5, TimeUnit.SECONDS));
		assertEquals(3, FLAKY_ATTEMPTS.get());
	}

}
