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

	/**
	 * Creates the exception for a failed response.
	 *
	 * @param status    the response's HTTP status
	 * @param rawBody   the response's raw body
	 * @param errorBody the response body deserialized into the method's
	 *                  {@code @ErrorType}, or {@code rawBody} itself if the
	 *                  method has no {@code @ErrorType}
	 */
	public RestInPeaceHttpException(int status, String rawBody, Object errorBody) {
		super(String.format("Request failed with HTTP status %d.", status));
		this.status = status;
		this.rawBody = rawBody;
		this.errorBody = errorBody;
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
	 * Whether the response's status is exactly {@code status} - a shorthand
	 * for {@code getStatus() == status} at a call site (e.g. {@code e.is(404)}).
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

}
