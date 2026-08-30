package com.shri.restinpeace.exception;

/**
 * Unchecked exception thrown for any REST-in-peace runtime error - a
 * {@code @RestClient} interface missing {@code @RestClient} or failing
 * validation (see {@link RestInPeaceValidationException} for the underlying
 * validation errors), a missing required parameter value, or an
 * unsupported HTTP method/annotation combination.
 */
public class RestInPeaceException extends RuntimeException {

	private static final long serialVersionUID = 8111958117033765908L;

	/** Creates an exception with no message or cause. */
	public RestInPeaceException() {
		super();
	}

	/**
	 * Creates an exception with a message and cause.
	 *
	 * @param message a description of what went wrong
	 * @param cause   the underlying cause
	 */
	public RestInPeaceException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * Creates an exception with a message.
	 *
	 * @param message a description of what went wrong
	 */
	public RestInPeaceException(String message) {
		super(message);
	}

	/**
	 * Creates an exception with a cause.
	 *
	 * @param cause the underlying cause
	 */
	public RestInPeaceException(Throwable cause) {
		super(cause);
	}

}
