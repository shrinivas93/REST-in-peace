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
 * each one - unless the failed response itself carries a {@code Retry-After}
 * header, which takes priority over the computed delay for that one wait
 * (see {@link #delayMillis()}), and {@link #jitterFactor()} randomizes each
 * computed delay to avoid many callers retrying in lockstep.
 *
 * <p>
 * Works on both a synchronous return type and a {@code CompletableFuture}
 * one - the async case schedules each retry on a background thread instead
 * of blocking the caller. Every attempt, including ones that get retried,
 * is still reported to any registered {@link com.shri.restinpeace.interceptor.RequestInterceptor}'s
 * {@code afterResponse}.
 *
 * <p>
 * Retrying is only safe by default for a method whose HTTP verb is already
 * idempotent ({@code GET}/{@code PUT}/{@code DELETE}) - retrying a
 * {@code POST}/{@code PATCH} that actually succeeded server-side but whose
 * response was lost in transit (a timeout or dropped connection after the
 * server committed) risks double-executing it (a duplicate charge, a
 * duplicate order). {@link #idempotent()} closes that gap: it generates one
 * {@code Idempotency-Key} header value and holds it identical across every
 * attempt of a given call, so a server that honors idempotency keys (as
 * Stripe, PayPal, Adyen, and Square all do) can recognize a retried attempt
 * as the same logical request instead of a new one.
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
	 * How long to wait before the first retry, in milliseconds - overridden
	 * for that one wait whenever the response being retried carries a
	 * {@code Retry-After} header (as a delta-seconds value or an HTTP-date),
	 * since the server's own stated wait time is more authoritative than a
	 * value fixed at compile time. A transport failure (no response at all)
	 * has no header to read, so it always falls back to this value.
	 *
	 * @return the initial delay in milliseconds
	 */
	long delayMillis() default 200;

	/**
	 * The factor the delay is multiplied by after each retry - {@code 1.0}
	 * for a fixed delay, greater than {@code 1.0} for exponential backoff.
	 * Applied to the previous attempt's own delay, whether that delay came
	 * from this computation or from a {@code Retry-After} header.
	 *
	 * @return the backoff multiplier
	 */
	double backoffMultiplier() default 2.0;

	/**
	 * Randomizes each computed delay (not one taken from a
	 * {@code Retry-After} header, which is already an explicit server
	 * instruction) by up to this fraction in either direction, so that many
	 * callers who all started retrying at the same moment - e.g. every
	 * replica of a horizontally-scaled service hitting the same downstream -
	 * don't retry in exact lockstep. {@code 0.0} (the default) applies no
	 * jitter, exactly reproducing the delay sequence
	 * {@link #delayMillis()}/{@link #backoffMultiplier()} alone would
	 * produce; {@code 1.0} allows the delay to be scaled anywhere from zero
	 * to double. Must be between {@code 0.0} and {@code 1.0} inclusive.
	 *
	 * @return the jitter fraction
	 */
	double jitterFactor() default 0.0;

	/**
	 * The HTTP status codes that count as a failure worth retrying. A
	 * transport error (no response at all) is always retried regardless of
	 * this list.
	 *
	 * @return the retryable status codes
	 */
	int[] retryOnStatus() default { 429, 502, 503, 504 };

	/**
	 * If true, sends an {@code Idempotency-Key} header - one randomly
	 * generated value per call, held identical across every retry attempt
	 * of that call (never regenerated between attempts, never shared with
	 * any other call) - the standard way (Stripe, PayPal, Adyen, Square)
	 * to let a server tell a genuine retry of the same request apart from
	 * a new one. Most meaningful on {@code POST}/{@code PATCH}, the two
	 * methods HTTP itself doesn't already guarantee are safe to retry;
	 * harmless (but redundant) on {@code GET}/{@code PUT}/{@code DELETE}.
	 *
	 * @return whether to send a stable {@code Idempotency-Key}
	 */
	boolean idempotent() default false;

}
