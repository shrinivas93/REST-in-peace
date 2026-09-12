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
