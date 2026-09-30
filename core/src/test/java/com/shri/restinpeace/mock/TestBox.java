package com.shri.restinpeace.mock;

import java.util.Objects;

/**
 * A minimal generic wrapper standing in for a real adapted return type
 * (e.g. Project Reactor's {@code Mono<T>}) in tests, so
 * {@link com.shri.restinpeace.CallAdapter} dispatch can be exercised
 * without a dependency on any actual reactive library. A
 * {@link com.shri.restinpeace.CallAdapter}'s own
 * {@code adapt(CompletableFuture<Object> delegate)} already hands this
 * class an already-completed future's already-produced value (or error) at
 * construction time via {@link #of}/{@link #failed} - {@link #get} just
 * returns or throws what it was built with, synchronously; there's no
 * future stored here to block on. Intentionally simple, since proving the
 * dispatch/pipeline wiring is the point, not building a real async wrapper
 * type.
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
		// A null error would otherwise silently build a "successful" box -
		// isFailed() reads false and get() returns null instead of throwing -
		// which would hide a genuine dispatch failure a test relies on this
		// factory method to carry faithfully.
		Objects.requireNonNull(error, "error");
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
