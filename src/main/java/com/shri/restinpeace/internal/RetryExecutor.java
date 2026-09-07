package com.shri.restinpeace.internal;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
 */
final class RetryExecutor {

	private static final int[] EMPTY_STATUS_CODES = new int[0];

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
			return executeSyncWithRetry(errorType, returnType, context, call, false, 0, 0L, 1.0, EMPTY_STATUS_CODES);
		}
		return executeSyncWithRetry(errorType, returnType, context, call, true, retry.times(), retry.delayMillis(),
				retry.backoffMultiplier(), retry.retryOnStatus());
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
			int[] retryOnStatus) {
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
			sleep(delay);
			delay = nextDelay(delay, backoffMultiplier);
		}
	}

	<B> CompletableFuture<HttpResponse<B>> executeAsyncWithRetry(Method method, Class<?> returnType,
			RequestContext context, Supplier<CompletableFuture<HttpResponse<B>>> call) {
		Class<?> errorType = ResponseDecoder.errorTypeOf(method);
		Retry retry = method.getAnnotation(Retry.class);
		if (retry == null) {
			return executeAsyncWithRetry(errorType, returnType, context, call, false, 0, 0L, 1.0, EMPTY_STATUS_CODES);
		}
		return executeAsyncWithRetry(errorType, returnType, context, call, true, retry.times(), retry.delayMillis(),
				retry.backoffMultiplier(), retry.retryOnStatus());
	}

	/**
	 * Non-reflective counterpart taking {@code @Retry}'s values and
	 * {@code @ErrorType}'s value as literal arguments, mirroring
	 * {@link #executeSyncWithRetry(Class, Class, RequestContext, Supplier, boolean, int, long, double, int[])}
	 * for the async path.
	 */
	<B> CompletableFuture<HttpResponse<B>> executeAsyncWithRetry(Class<?> errorType, Class<?> returnType,
			RequestContext context, Supplier<CompletableFuture<HttpResponse<B>>> call, boolean hasRetry, int times,
			long delayMillis, double backoffMultiplier, int[] retryOnStatus) {
		if (!hasRetry) {
			return call.get().thenApply(response -> {
				interceptorDispatcher.notifyAfterResponse(context, response, errorType, returnType);
				return response;
			});
		}
		return attemptAsync(call, errorType, returnType, context, times, backoffMultiplier, retryOnStatus, 1,
				delayMillis);
	}

	private <B> CompletableFuture<HttpResponse<B>> attemptAsync(Supplier<CompletableFuture<HttpResponse<B>>> call,
			Class<?> errorType, Class<?> returnType, RequestContext context, int times, double backoffMultiplier,
			int[] retryOnStatus, int attempt, long delay) {
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
			RETRY_SCHEDULER.schedule(
					() -> attemptAsync(call, errorType, returnType, context, times, backoffMultiplier, retryOnStatus,
							attempt + 1, nextDelay(delay, backoffMultiplier))
							.whenComplete((r, t) -> {
								if (t != null) {
									result.completeExceptionally(t);
								} else {
									result.complete(r);
								}
							}),
					delay, TimeUnit.MILLISECONDS);
		});
		return result;
	}

	private static boolean isRetryableStatus(int status, int[] retryOnStatus) {
		return IntStream.of(retryOnStatus).anyMatch(code -> code == status);
	}

	private static long nextDelay(long delay, double backoffMultiplier) {
		return (long) (delay * backoffMultiplier);
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
