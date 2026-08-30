package com.shri.restinpeace.annotation.retry;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Re-issues a request that fails with a transport error (a connection
 * refused, a timeout) or with one of {@link #retryOnStatus()}, up to
 * {@link #times()} attempts total, waiting {@link #delayMillis()} between
 * attempts and multiplying that wait by {@link #backoffMultiplier()} after
 * each one.
 *
 * <p>
 * Works on both a synchronous return type and a {@code CompletableFuture}
 * one - the async case schedules each retry on a background thread instead
 * of blocking the caller. Every attempt, including ones that get retried,
 * is still reported to any registered {@link com.shri.restinpeace.interceptor.RequestInterceptor}'s
 * {@code afterResponse}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Retry {

	/**
	 * How many attempts to make in total before giving up and returning (or
	 * throwing) the last outcome. Must be at least 1.
	 *
	 * @return the maximum number of attempts
	 */
	int times() default 3;

	/**
	 * How long to wait before the first retry, in milliseconds.
	 *
	 * @return the initial delay in milliseconds
	 */
	long delayMillis() default 200;

	/**
	 * The factor the delay is multiplied by after each retry - {@code 1.0}
	 * for a fixed delay, greater than {@code 1.0} for exponential backoff.
	 *
	 * @return the backoff multiplier
	 */
	double backoffMultiplier() default 2.0;

	/**
	 * The HTTP status codes that count as a failure worth retrying. A
	 * transport error (no response at all) is always retried regardless of
	 * this list.
	 *
	 * @return the retryable status codes
	 */
	int[] retryOnStatus() default { 429, 502, 503, 504 };

}
