package com.shri.restinpeace.reactor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import reactor.core.publisher.Mono;

/**
 * Bridges an already-dispatched {@link CompletableFuture} into a {@link Mono}
 * - shared by {@link MonoCallAdapterFactory} and
 * {@link FluxListCallAdapterFactory}, whose {@code adapt(...)} bodies were
 * previously near-identical copies of this exact logic (including the same
 * cross-subscriber cancellation bug both had independently).
 *
 * <p>
 * <b>Reference-counted cancellation:</b> {@code delegate} is dispatched once,
 * eagerly, before {@code adapt(...)} is ever called (see
 * {@code RequestExecutor.dispatchViaCallAdapter}) - but the {@link Mono} this
 * returns can still be subscribed to more than once (ordinary
 * {@code Mono.create} replay-to-multiple-subscribers semantics), and each
 * subscription independently wires {@code sink.onCancel(...)} to the same
 * shared {@code delegate}. Without counting live subscriptions, subscriber
 * A's {@code dispose()} would cancel the future subscriber B is still
 * awaiting. {@code delegate} is only actually cancelled once every
 * subscription that has disposed accounts for all subscriptions currently
 * registered - i.e. the last one out turns off the lights.
 */
final class FutureMono {

	private FutureMono() {
	}

	static <T> Mono<T> from(CompletableFuture<Object> delegate, Function<Object, T> decode) {
		AtomicInteger liveSubscriptions = new AtomicInteger();
		return Mono.create(sink -> {
			liveSubscriptions.incrementAndGet();
			delegate.whenComplete((value, error) -> {
				if (error != null) {
					sink.error(
							error instanceof CompletionException && error.getCause() != null ? error.getCause() : error);
				} else {
					sink.success(decode.apply(value));
				}
			});
			sink.onCancel(() -> {
				if (liveSubscriptions.decrementAndGet() == 0) {
					delegate.cancel(true);
				}
			});
		});
	}

}
