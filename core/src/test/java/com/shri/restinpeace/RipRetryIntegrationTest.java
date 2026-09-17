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

	@Test
	void retry_drivenPurelyByRipClientConfig_succeedsAfterRetrying_withNoAnnotationAtAll() {
		LocalApi api = RIP.getClient(LocalApi.class, RipClientConfig.builder()
				.retry(RetryConfig.builder().times(3).delayMillis(5).retryOnStatus(503).build()).build());

		String result = api.getFlakyWithNoRetryAnnotation(port, "z");

		assertEquals("ok", result);
		assertEquals(3, FLAKY_ATTEMPTS.get());
	}

	@Test
	void retry_asyncWithPermanentFailure_completesExceptionallyAfterConfiguredAttempts() {
		LocalApi api = RIP.getClient(LocalApi.class);

		CompletableFuture<String> future = api.getAlwaysFailingWithRetryAsync(port, "x");

		ExecutionException exception = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
		assertEquals(RestInPeaceHttpException.class, exception.getCause().getClass());
		assertEquals(503, ((RestInPeaceHttpException) exception.getCause()).getStatus());
		assertEquals(3, ALWAYS_FAILING_ATTEMPTS.get());
	}

	@Test
	void retry_asyncDrivenPurelyByRipClientConfig_idempotentSendsAStableKeyAcrossEveryAttempt()
			throws InterruptedException, ExecutionException, TimeoutException {
		LocalApi api = RIP.getClient(LocalApi.class, RipClientConfig.builder().retry(RetryConfig.builder().times(3)
				.delayMillis(5).retryOnStatus(503).idempotent(true).build()).build());

		String result = api.getFlakyWithNoRetryAnnotationAsync(port, "idempotent-async").get(5, TimeUnit.SECONDS);

		assertEquals("ok", result);
		assertEquals(3, IDEMPOTENCY_KEYS_SEEN.size());
		assertNotNull(IDEMPOTENCY_KEYS_SEEN.get(0));
		assertEquals(IDEMPOTENCY_KEYS_SEEN.get(0), IDEMPOTENCY_KEYS_SEEN.get(1));
		assertEquals(IDEMPOTENCY_KEYS_SEEN.get(0), IDEMPOTENCY_KEYS_SEEN.get(2));
	}

	@Test
	void retry_asyncWithExhaustedRetryBudget_stopsRetryingEarlyAndFails()
			throws InterruptedException, TimeoutException {
		LocalApi api = RIP.getClient(LocalApi.class, RipClientConfig.builder().retryBudget(1, 60_000).build());

		CompletableFuture<String> future = api.getFlakyAsync(port, "budget");

		ExecutionException exception = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
		assertEquals(RestInPeaceHttpException.class, exception.getCause().getClass());
		// The 1-retry budget lets attempt 2 happen but blocks attempt 3, so the
		// flaky endpoint (which only succeeds from its 3rd call onward) never
		// gets the chance to return 200.
		assertEquals(2, FLAKY_ATTEMPTS.get());
	}

	@Test
	void retry_withJitterFactor_stillSucceedsAfterRetrying() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.getFlakyWithJitter(port, "jitter");

		assertEquals("ok", result);
		assertEquals(3, FLAKY_ATTEMPTS.get());
	}

	@Test
	void retry_withHttpDateRetryAfterHeader_parsesItAndStillSucceeds() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.getFlaky(port, "retry-after-date");

		assertEquals("ok", result);
		assertEquals(3, FLAKY_ATTEMPTS.get());
	}

	@Test
	void retry_withUnparsableRetryAfterHeader_fallsBackToTheComputedDelay() {
		LocalApi api = RIP.getClient(LocalApi.class);

		String result = api.getFlaky(port, "retry-after-garbage");

		assertEquals("ok", result);
		assertEquals(3, FLAKY_ATTEMPTS.get());
	}

	@Test
	void retry_methodAnnotation_winsOverRipClientConfigsDefault() {
		// @Retry(times = 3) on getFlaky itself must win over this client's own
		// times = 1 default - if the config wrongly took over, this would give up
		// after the first 503 instead of succeeding on the third attempt.
		LocalApi api = RIP.getClient(LocalApi.class,
				RipClientConfig.builder().retry(RetryConfig.builder().times(1).build()).build());

		String result = api.getFlaky(port, "z");

		assertEquals("ok", result);
		assertEquals(3, FLAKY_ATTEMPTS.get());
	}

}
