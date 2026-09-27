package com.shri.restinpeace.mock;

/**
 * A minimal generic wrapper standing in for a real adapted return type
 * (e.g. Project Reactor's {@code Mono<T>}) in tests, so
 * {@link com.shri.restinpeace.CallAdapter} dispatch can be exercised
 * without a dependency on any actual reactive library. Resolves
 * synchronously via {@link #get()} (blocking on the underlying future) -
 * intentionally simple, since proving the dispatch/pipeline wiring is the
 * point, not building a real async wrapper type.
 *
 * @param <T> the decoded value's type
 */
public final class TestBox<T> {

	private final T value;
	private final RuntimeException error;

	private TestBox(T value, RuntimeException error) {
		this.value = value;
		this.error = error;
	}

	public static <T> TestBox<T> of(T value) {
		return new TestBox<>(value, null);
	}

	public static <T> TestBox<T> failed(RuntimeException error) {
		return new TestBox<>(null, error);
	}

	/**
	 * @return the decoded value
	 * @throws RuntimeException the exact exception the underlying call
	 *                          failed with, if it failed
	 */
	public T get() {
		if (error != null) {
			throw error;
		}
		return value;
	}

	public boolean isFailed() {
		return error != null;
	}

}
