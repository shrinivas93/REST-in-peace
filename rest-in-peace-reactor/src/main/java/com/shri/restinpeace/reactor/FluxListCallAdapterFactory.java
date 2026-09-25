package com.shri.restinpeace.reactor;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import com.shri.restinpeace.CallAdapter;
import com.shri.restinpeace.CallAdapterFactory;
import com.shri.restinpeace.annotation.pagination.Paginated;

/**
 * Claims a plain (non-{@code @Paginated}) {@code Flux<T>}-returning
 * {@code @RestClient} method - "flavor 1" of {@code Flux<T>} support
 * (§7.1): decodes the response body as {@code List<T>} (RIP's existing
 * generic-collection decoding, E9/E12) then emits it item-by-item via
 * {@link Flux#fromIterable}. There's no real backpressure here - the whole
 * list is already decoded in memory before this adapter ever runs, so this
 * flavor is a convenience for a consumer already writing Reactor-style
 * pipelines, not a memory-efficiency feature. See
 * {@link FluxPaginatedCallAdapterFactory} ("flavor 2") for the one that
 * actually earns backpressure through real page-at-a-time fetching.
 *
 * <p>
 * <b>Declines a {@code @Paginated} method (§7.3):</b> both flavors declare
 * the same {@code Flux<T>} return type, so return type alone can't
 * disambiguate them - {@code @Paginated}'s presence is the signal
 * {@code RequestExecutor} already uses (a {@code @Paginated} method is
 * routed to pagination-aware dispatch before any {@link CallAdapterFactory}
 * resolution even runs), so this factory declines outright rather than ever
 * racing {@link FluxPaginatedCallAdapterFactory} for the same method.
 */
public final class FluxListCallAdapterFactory implements CallAdapterFactory {

	@Override
	public Optional<CallAdapter<?>> get(Method method) {
		if (method.getReturnType() != Flux.class) {
			return Optional.empty();
		}
		if (method.getAnnotation(Paginated.class) != null) {
			return Optional.empty();
		}
		Type genericReturnType = method.getGenericReturnType();
		if (!(genericReturnType instanceof ParameterizedType)) {
			return Optional.empty();
		}
		Type itemType = ((ParameterizedType) genericReturnType).getActualTypeArguments()[0];
		return Optional.of(new FluxListCallAdapter(listOf(itemType)));
	}

	private static final class FluxListCallAdapter implements CallAdapter<Flux<Object>> {

		private final Type listType;

		private FluxListCallAdapter(Type listType) {
			this.listType = listType;
		}

		@Override
		public Type responseBodyType() {
			return listType;
		}

		@Override
		@SuppressWarnings("unchecked")
		public Flux<Object> adapt(CompletableFuture<Object> delegate) {
			return Mono.<List<Object>>create(sink -> {
				delegate.whenComplete((value, error) -> {
					if (error != null) {
						sink.error(error instanceof CompletionException && error.getCause() != null
								? error.getCause() : error);
					} else {
						sink.success((List<Object>) value);
					}
				});
				sink.onCancel(() -> delegate.cancel(true));
			}).flatMapMany(Flux::fromIterable);
		}

	}

	private static Type listOf(Type itemType) {
		return new ParameterizedType() {
			@Override
			public Type[] getActualTypeArguments() {
				return new Type[] { itemType };
			}

			@Override
			public Type getRawType() {
				return List.class;
			}

			@Override
			public Type getOwnerType() {
				return null;
			}
		};
	}

}
