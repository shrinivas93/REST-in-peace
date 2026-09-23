package com.shri.restinpeace.reactor;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import reactor.core.publisher.Mono;

import com.shri.restinpeace.CallAdapter;
import com.shri.restinpeace.CallAdapterFactory;

/**
 * Claims every {@code Mono<T>}-returning {@code @RestClient} method -
 * {@code Mono<Void>}, {@code Mono<RipResponse<T>>}, and {@code Mono<byte[]>}
 * all work identically, since {@code responseBodyType()} just names {@code T}
 * verbatim and {@code RequestExecutor}'s existing dispatch already handles
 * every one of those shapes for any {@link CallAdapter}. See
 * {@code docs/design/reactor-call-adapter.md} §6.
 *
 * <p>
 * <b>Eager, not deferred (§6.1):</b> the method call that returns a
 * {@code Mono<T>} has already dispatched the HTTP request by the time it
 * returns - the same convention {@code CompletableFuture<T>} already
 * follows elsewhere in RIP. Subscribing only observes the outcome; it never
 * triggers the call. A consumer who wants Reactor's usual defer-until-
 * subscribed semantics wraps it themselves: {@code Mono.defer(() -> api.getUser(id))}.
 */
public final class MonoCallAdapterFactory implements CallAdapterFactory {

	/**
	 * A raw {@code Mono} (no type parameter) declines rather than throws -
	 * {@link com.shri.restinpeace.internal.RequestExecutor#resolveCallAdapter}
	 * is called from {@code ReflectiveRestClientValidator}'s own per-method
	 * validation loop too, which collects every problem via
	 * {@code ValidationResult.addError(...)} rather than throwing mid-loop
	 * (throwing here would abort validation for every other method on the
	 * same interface with an uncaught exception instead of a clean,
	 * collected error). Declining instead lets a raw {@code Mono} fall
	 * through to the existing {@code KNOWN_UNSUPPORTED_REACTIVE_TYPES}
	 * denylist check from chunk 2 - {@code Mono.class}'s own fully-qualified
	 * name is already on that list - reporting it the same clean way an
	 * unclaimed {@code Mono<T>} already is.
	 */
	@Override
	public Optional<CallAdapter<?>> get(Method method) {
		if (method.getReturnType() != Mono.class) {
			return Optional.empty();
		}
		Type genericReturnType = method.getGenericReturnType();
		if (!(genericReturnType instanceof ParameterizedType)) {
			return Optional.empty();
		}
		Type innerType = ((ParameterizedType) genericReturnType).getActualTypeArguments()[0];
		return Optional.of(new MonoCallAdapter(innerType));
	}

	private static final class MonoCallAdapter implements CallAdapter<Mono<Object>> {

		private final Type responseBodyType;

		private MonoCallAdapter(Type responseBodyType) {
			this.responseBodyType = responseBodyType;
		}

		@Override
		public Type responseBodyType() {
			return responseBodyType;
		}

		/**
		 * {@code Mono.create} (not {@code Mono.fromFuture}) specifically so
		 * {@code sink.onCancel(...)} can wire a downstream cancellation (a
		 * fired {@code .timeout(...)}, an explicit {@code Disposable.dispose()})
		 * into {@code CompletableFuture#cancel(true)} - which genuinely aborts
		 * the underlying Apache HttpClient async request, not merely
		 * abandons interest in a result that keeps computing anyway. See §6.2.
		 */
		@Override
		public Mono<Object> adapt(CompletableFuture<Object> delegate) {
			return Mono.create(sink -> {
				delegate.whenComplete((value, error) -> {
					if (error != null) {
						sink.error(error instanceof CompletionException && error.getCause() != null ? error.getCause()
								: error);
					} else {
						sink.success(value);
					}
				});
				sink.onCancel(() -> delegate.cancel(true));
			});
		}

	}

}
