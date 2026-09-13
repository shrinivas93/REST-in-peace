package com.shri.restinpeace.internal;

import java.lang.reflect.Method;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import com.shri.restinpeace.annotation.retry.Retry;
import com.shri.restinpeace.exception.RestInPeaceException;
import com.shri.restinpeace.interceptor.RequestContext;

import kong.unirest.HttpResponse;

/**
 * Executes a {@code @Retry}'d call - a plain blocking loop for a
 * synchronous return type, a chain of {@link CompletableFuture}s scheduled
 * on a background thread for an async one - re-issuing on a transport
 * failure or a matching status code. Extracted out of {@link RequestExecutor}
 * since the retry loop, sync and async, was its own genuinely separate
 * concern (and the largest cluster after caching); reports every attempt,
 * including retried ones, to {@code interceptorDispatcher} for interceptor
 * notification.
 *
 * <p>
 * The wait before each retry is, in order of priority: the response's own
 * {@code Retry-After} header (delta-seconds or an HTTP-date; only ever
 * consulted for an actual response, never for a transport failure, which has
 * no header to read) if present, otherwise the computed delay
 * ({@link Retry#delayMillis()}, multiplied by {@link Retry#backoffMultiplier()}
 * after each attempt) with {@link Retry#jitterFactor()} randomization
 * applied. {@link Retry#backoffMultiplier()} itself always compounds onto
 * whichever wait was actually used for the previous attempt, so a run of
 * {@code Retry-After}-guided waits still keeps growing if the server keeps
 * asking for longer ones.
 */
final class RetryExecutor {

	private static final int[] EMPTY_STATUS_CODES = new int[0];

	private static final String RETRY_AFTER_HEADER = "Retry-After";

	private static final ScheduledExecutorService RETRY_SCHEDULER = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "rip-retry-scheduler");
		thread.setDaemon(true);
		return thread;
	});

	private final InterceptorDispatcher interceptorDispatcher;

	RetryExecutor(InterceptorDispatcher interceptorDispatcher) {
		this.interceptorDispatcher = interceptorDispatcher;
	}

	<B> HttpResponse<B> executeSyncWithRetry(Method method, Class<?> returnType, RequestContext context,
			Supplier<HttpResponse<B>> call) {
		Class<?> errorType = ResponseDecoder.errorTypeOf(method);
		Retry retry = method == null ? null : method.getAnnotation(Retry.class);
		if (retry == null) {
			return executeSyncWithRetry(errorType, returnType, context, call, false, 0, 0L, 1.0, 0.0, EMPTY_STATUS_CODES);
		}
		return executeSyncWithRetry(errorType, returnType, context, call, true, retry.times(), retry.delayMillis(),
				retry.backoffMultiplier(), retry.jitterFactor(), retry.retryOnStatus());
	}

	/**
	 * Non-reflective counterpart taking {@code @Retry}'s values and
	 * {@code @ErrorType}'s value as literal arguments instead of annotation
	 * lookups, shared by the reflective path above (which derives both from
	 * {@code method}) and every {@code processGenerated*} entry point used
	 * by compile-time-generated code, which has both as compile-time
	 * literals (or {@code null}/{@code false} if the method has neither).
	 */
	<B> HttpResponse<B> executeSyncWithRetry(Class<?> errorType, Class<?> returnType, RequestContext context,
			Supplier<HttpResponse<B>> call, boolean hasRetry, int times, long delayMillis, double backoffMultiplier,
			double jitterFactor, int[] retryOnStatus) {
		if (!hasRetry) {
			HttpResponse<B> response = call.get();
			interceptorDispatcher.notifyAfterResponse(context, response, errorType, returnType);
			return response;
		}
		long delay = delayMillis;
		for (int attempt = 1;; attempt++) {
			HttpResponse<B> response = null;
			RuntimeException failure = null;
			try {
				response = call.get();
				interceptorDispatcher.notifyAfterResponse(context, response, errorType, returnType);
			} catch (RuntimeException e) {
				failure = e;
			}
			boolean retryable = failure != null || isRetryableStatus(response.getStatus(), retryOnStatus);
			if (!retryable || attempt >= times) {
				if (failure != null) {
					throw failure;
				}
				return response;
			}
			Long retryAfterMillis = failure == null ? parseRetryAfterMillis(response) : null;
			long waitMillis = retryAfterMillis != null ? retryAfterMillis : applyJitter(delay, jitterFactor);
			sleep(waitMillis);
			delay = nextDelay(retryAfterMillis != null ? retryAfterMillis : delay, backoffMultiplier);
		}
	}

	<B> CompletableFuture<HttpResponse<B>> executeAsyncWithRetry(Method method, Class<?> returnType,
			RequestContext context, Supplier<CompletableFuture<HttpResponse<B>>> call) {
		Class<?> errorType = ResponseDecoder.errorTypeOf(method);
		Retry retry = method.getAnnotation(Retry.class);
		if (retry == null) {
			return executeAsyncWithRetry(errorType, returnType, context, call, false, 0, 0L, 1.0, 0.0, EMPTY_STATUS_CODES);
		}
		return executeAsyncWithRetry(errorType, returnType, context, call, true, retry.times(), retry.delayMillis(),
				retry.backoffMultiplier(), retry.jitterFactor(), retry.retryOnStatus());
	}

	/**
	 * Non-reflective counterpart taking {@code @Retry}'s values and
	 * {@code @ErrorType}'s value as literal arguments, mirroring
	 * {@link #executeSyncWithRetry(Class, Class, RequestContext, Supplier, boolean, int, long, double, double, int[])}
	 * for the async path.
	 */
	<B> CompletableFuture<HttpResponse<B>> executeAsyncWithRetry(Class<?> errorType, Class<?> returnType,
			RequestContext context, Supplier<CompletableFuture<HttpResponse<B>>> call, boolean hasRetry, int times,
			long delayMillis, double backoffMultiplier, double jitterFactor, int[] retryOnStatus) {
		if (!hasRetry) {
			return call.get().thenApply(response -> {
				interceptorDispatcher.notifyAfterResponse(context, response, errorType, returnType);
				return response;
			});
		}
		return attemptAsync(call, errorType, returnType, context, times, backoffMultiplier, jitterFactor, retryOnStatus,
				1, delayMillis);
	}

	private <B> CompletableFuture<HttpResponse<B>> attemptAsync(Supplier<CompletableFuture<HttpResponse<B>>> call,
			Class<?> errorType, Class<?> returnType, RequestContext context, int times, double backoffMultiplier,
			double jitterFactor, int[] retryOnStatus, int attempt, long delay) {
		CompletableFuture<HttpResponse<B>> result = new CompletableFuture<>();
		call.get().whenComplete((response, failure) -> {
			if (response != null) {
				interceptorDispatcher.notifyAfterResponse(context, response, errorType, returnType);
			}
			boolean retryable = failure != null || isRetryableStatus(response.getStatus(), retryOnStatus);
			if (!retryable || attempt >= times) {
				if (failure != null) {
					result.completeExceptionally(failure);
				} else {
					result.complete(response);
				}
				return;
			}
			Long retryAfterMillis = failure == null ? parseRetryAfterMillis(response) : null;
			long waitMillis = retryAfterMillis != null ? retryAfterMillis : applyJitter(delay, jitterFactor);
			long nextDelay = nextDelay(retryAfterMillis != null ? retryAfterMillis : delay, backoffMultiplier);
			RETRY_SCHEDULER.schedule(
					() -> attemptAsync(call, errorType, returnType, context, times, backoffMultiplier, jitterFactor,
							retryOnStatus, attempt + 1, nextDelay)
							.whenComplete((r, t) -> {
								if (t != null) {
									result.completeExceptionally(t);
								} else {
									result.complete(r);
								}
							}),
					waitMillis, TimeUnit.MILLISECONDS);
		});
		return result;
	}

	private static boolean isRetryableStatus(int status, int[] retryOnStatus) {
		return IntStream.of(retryOnStatus).anyMatch(code -> code == status);
	}

	private static long nextDelay(long delay, double backoffMultiplier) {
		return (long) (delay * backoffMultiplier);
	}

	/**
	 * Randomizes {@code delay} by up to {@code jitterFactor} in either
	 * direction ({@code delay * (1 ± jitterFactor)}, floored at zero) -
	 * {@code jitterFactor <= 0.0} (the annotation's default) returns
	 * {@code delay} unchanged, so existing {@code @Retry} usages see no
	 * behavior change at all.
	 */
	private static long applyJitter(long delay, double jitterFactor) {
		if (jitterFactor <= 0.0) {
			return delay;
		}
		double randomizedFactor = 1.0 + (ThreadLocalRandom.current().nextDouble() * 2.0 - 1.0) * jitterFactor;
		return Math.max(0L, Math.round(delay * randomizedFactor));
	}

	/**
	 * Reads the response's {@code Retry-After} header, as either
	 * delta-seconds (a plain integer, converted to milliseconds) or an
	 * HTTP-date (RFC 1123, e.g. {@code "Wed, 21 Oct 2015 07:28:00 GMT"},
	 * converted to the number of milliseconds from now until then, floored
	 * at zero for a date already in the past).
	 *
	 * @return the header's value in milliseconds, or {@code null} if the
	 *         header is absent or its value is in neither supported format
	 */
	private static Long parseRetryAfterMillis(HttpResponse<?> response) {
		String value = response.getHeaders().getFirst(RETRY_AFTER_HEADER);
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		String trimmed = value.trim();
		try {
			return Long.parseLong(trimmed) * 1000L;
		} catch (NumberFormatException notDeltaSeconds) {
			try {
				ZonedDateTime target = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME);
				long millisUntilTarget = target.toInstant().toEpochMilli() - System.currentTimeMillis();
				return Math.max(0L, millisUntilTarget);
			} catch (DateTimeParseException notAnHttpDate) {
				return null;
			}
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RestInPeaceException("Interrupted while waiting to retry.", e);
		}
	}

}
