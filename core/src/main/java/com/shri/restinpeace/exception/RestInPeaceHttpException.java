package com.shri.restinpeace.exception;

/**
 * Unchecked exception thrown when a {@code @RestClient} method's response
 * has a non-2xx HTTP status - the request reached the server and got a
 * response, it's just not a success. Carries the status and the raw
 * response body, plus the body deserialized into whatever class a
 * {@link com.shri.restinpeace.annotation.error.ErrorType @ErrorType} on the
 * method declared (or the raw body itself, as a {@code String}, if the
 * method has no {@code @ErrorType}).
 *
 * <p>
 * A transport failure (no response at all, e.g. connection refused) still
 * throws the underlying transport exception directly, not this one - this
 * exception specifically means "the server answered, and the answer was an
 * error".
 */
public class RestInPeaceHttpException extends RestInPeaceException {

	private static final long serialVersionUID = 1L;

	/** The response's HTTP status. */
	private final int status;
	/** The response's raw body. */
	private final String rawBody;
	/** The response body deserialized into the method's {@code @ErrorType}, or {@link #rawBody} itself. */
	private final Object errorBody;
	/** The response's own {@code Retry-After} header, parsed to milliseconds, or {@code null}. */
	private final Long retryAfterMillis;

	/**
	 * Creates the exception for a failed response with no {@code Retry-After}
	 * header to parse. Equivalent to {@code RestInPeaceHttpException(status,
	 * rawBody, errorBody, null)}.
	 *
	 * @param status    the response's HTTP status
	 * @param rawBody   the response's raw body
	 * @param errorBody the response body deserialized into the method's
	 *                  {@code @ErrorType}, or {@code rawBody} itself if the
	 *                  method has no {@code @ErrorType}
	 */
	public RestInPeaceHttpException(int status, String rawBody, Object errorBody) {
		this(status, rawBody, errorBody, null);
	}

	/**
	 * Creates the exception for a failed response.
	 *
	 * @param status           the response's HTTP status
	 * @param rawBody          the response's raw body
	 * @param errorBody        the response body deserialized into the
	 *                         method's {@code @ErrorType}, or {@code rawBody}
	 *                         itself if the method has no {@code @ErrorType}
	 * @param retryAfterMillis the response's own {@code Retry-After} header
	 *                         (delta-seconds or an HTTP-date), parsed to
	 *                         milliseconds from now, or {@code null} if the
	 *                         header was absent or unparseable
	 */
	public RestInPeaceHttpException(int status, String rawBody, Object errorBody, Long retryAfterMillis) {
		super(String.format("Request failed with HTTP status %d.", status));
		this.status = status;
		this.rawBody = rawBody;
		this.errorBody = errorBody;
		this.retryAfterMillis = retryAfterMillis;
	}

	/**
	 * Returns the response's HTTP status.
	 *
	 * @return the HTTP status
	 */
	public int getStatus() {
		return status;
	}

	/**
	 * Whether the response's status is in the {@code 4xx} range - the
	 * request itself was the problem (bad input, missing auth, not found),
	 * as opposed to {@link #isServerError()}.
	 *
	 * @return {@code true} for a {@code 4xx} status
	 */
	public boolean isClientError() {
		return status >= 400 && status < 500;
	}

	/**
	 * Whether the response's status is in the {@code 5xx} range - the
	 * server itself failed, as opposed to {@link #isClientError()}. Often
	 * the signal to retry (see {@code @Retry}'s default {@code retryOnStatus})
	 * rather than surface the error as the caller's own mistake.
	 *
	 * @return {@code true} for a {@code 5xx} status
	 */
	public boolean isServerError() {
		return status >= 500 && status < 600;
	}

	/**
	 * Whether the response's status is in the {@code 3xx} range. A redirect
	 * response is typically followed transparently by the underlying HTTP
	 * client before RIP ever sees a final status, so this is mostly relevant
	 * with redirect-following disabled at that layer, or for a status this
	 * library doesn't otherwise treat specially (a {@code 304} used for
	 * cache revalidation, for instance, is handled internally and never
	 * surfaces as this exception at all).
	 *
	 * @return {@code true} for a {@code 3xx} status
	 */
	public boolean isRedirect() {
		return status >= 300 && status < 400;
	}

	/**
	 * Whether the response's status is exactly {@code status} - a shorthand
	 * for {@code getStatus() == status} at a call site (e.g. {@code e.is(404)}).
	 *
	 * <p>
	 * <b>Ordering pitfall:</b> in an if/else chain, check {@code is(specific
	 * status)} <em>before</em> {@link #isClientError()}/{@link
	 * #isServerError()}, not after - since every status those two cover
	 * already falls in their range, a branch for one specific status placed
	 * after either check is unreachable dead code:
	 *
	 * <pre>
	 * if (e.isClientError()) { ... }   // matches 429 too - reached first
	 * else if (e.is(429)) { ... }      // dead code: 429 already handled above
	 * </pre>
	 *
	 * <pre>
	 * if (e.is(429)) { ... }           // specific case checked first
	 * else if (e.isClientError()) { ... }   // the rest of 4xx
	 * </pre>
	 *
	 * @param status the status to compare against
	 * @return {@code true} if this exception's status equals {@code status}
	 */
	public boolean is(int status) {
		return this.status == status;
	}

	/**
	 * Returns the response's raw body, regardless of whether the method
	 * declared an {@code @ErrorType}.
	 *
	 * @return the raw response body
	 */
	public String getRawBody() {
		return rawBody;
	}

	/**
	 * Returns the response body deserialized into the method's
	 * {@code @ErrorType}, or the raw body itself if the method has no
	 * {@code @ErrorType}. The caller is responsible for requesting the same
	 * type the method's {@code @ErrorType} declared.
	 *
	 * @param <T> the expected error body type
	 * @return the error body
	 */
	@SuppressWarnings("unchecked")
	public <T> T getErrorBody() {
		return (T) errorBody;
	}

	/**
	 * Returns the response's own {@code Retry-After} header, parsed to
	 * milliseconds from now - the exact same parsing {@code @Retry} itself
	 * uses internally (delta-seconds, a plain integer, or an HTTP-date per
	 * RFC 1123), surfaced here for a caller whose method has no
	 * {@code @Retry} at all (or one that gave up after exhausting its
	 * attempts) and wants to honor the server's own backoff hint manually.
	 *
	 * @return the {@code Retry-After} header's value in milliseconds, or
	 *         {@code null} if the header was absent or in neither supported
	 *         format
	 */
	public Long getRetryAfterMillis() {
		return retryAfterMillis;
	}

}
