package com.shri.restinpeace.exception;

/**
 * Thrown instead of making a call when its client's
 * {@link com.shri.restinpeace.BulkheadConfig bulkhead} has no free permit
 * (and, if {@link com.shri.restinpeace.BulkheadConfig#getMaxWaitDurationMillis()}
 * is set, none freed up within that wait either) - too many calls to this
 * client are already in flight. Not a {@link RestInPeaceHttpException}: no
 * actual HTTP response exists for a call that was never attempted.
 *
 * <p>
 * Unlike {@link CircuitOpenException}, this is a plain concurrency
 * throttle rather than a signal that the downstream is failing - a permit
 * can free up the moment an in-flight call completes, so {@code @Retry}
 * does <b>not</b> special-case this exception the way it does
 * {@link CircuitOpenException}: it falls through to the ordinary
 * "any transport-level failure is retryable" path, letting a retry's own
 * backoff delay give the bulkhead a chance to free a permit before trying
 * again.
 */
public class BulkheadFullException extends RestInPeaceException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates an exception with a message describing which client's
	 * bulkhead refused the call.
	 *
	 * @param message a description of which client's bulkhead is full
	 */
	public BulkheadFullException(String message) {
		super(message);
	}

}
