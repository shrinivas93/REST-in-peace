package com.shri.restinpeace.exception;

/**
 * Thrown instead of making a call when its client's
 * {@link com.shri.restinpeace.CircuitBreakerConfig circuit breaker} is open -
 * the downstream has recently shown a high enough failure rate that this
 * call is refused immediately, without attempting the network call (or even
 * a cache lookup), rather than paying the cost of finding out it would have
 * failed too. Not a {@link RestInPeaceHttpException}: no actual HTTP
 * response exists for a call that was never attempted.
 *
 * <p>
 * Never worth retrying within the same open window - every attempt would
 * fail identically until the breaker's cooldown elapses - so
 * {@code @Retry} treats this exception as immediately terminal instead of
 * retryable, unlike a transport failure or a matching status code.
 */
public class CircuitOpenException extends RestInPeaceException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates an exception with a message describing which client refused
	 * the call.
	 *
	 * @param message a description of which client's breaker is open
	 */
	public CircuitOpenException(String message) {
		super(message);
	}

}
