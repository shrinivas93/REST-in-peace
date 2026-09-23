package com.shri.restinpeace;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * Adapts a {@code @RestClient} call's outcome into a consumer-chosen return
 * type {@code T}, for a return-type shape RIP itself has no built-in
 * support for (e.g. Project Reactor's {@code Mono<T>}/{@code Flux<T>}, via
 * the separate {@code rest-in-peace-reactor} module). Registered via
 * {@link RIP#addCallAdapterFactory(CallAdapterFactory)}. See
 * {@code docs/design/reactor-call-adapter.md} §5.
 *
 * <p>
 * An adapter never dispatches its own HTTP call - {@link #adapt} only ever
 * transforms the {@link CompletableFuture} RIP's own async dispatch path
 * already produced, guaranteeing the adapted call passes through the
 * identical pipeline (retry, cache, circuit breaker, bulkhead,
 * interceptors) as every other RIP call, and that it is dispatched exactly
 * once.
 *
 * @param <T> the adapted return type this instance produces
 */
public interface CallAdapter<T> {

	/**
	 * The type to decode the HTTP response body into - what {@code T}
	 * itself wraps (e.g. for a {@code Mono<User>} adapter, this returns
	 * {@code User.class}; for {@code Mono<RipResponse<User>>}, this returns
	 * a {@code RipResponse<User>} parameterized type), the same role
	 * {@link RipResponse}'s own inner-type resolution already plays for
	 * that return type.
	 *
	 * @return the type to decode the response body into
	 */
	Type responseBodyType();

	/**
	 * Adapts one already-in-flight call into {@code T}.
	 *
	 * @param delegate the in-flight call's future - the decoded body on
	 *                  success (per {@link #responseBodyType()}), or
	 *                  completed exceptionally with the same exception RIP
	 *                  throws for any other return type on failure (a
	 *                  transport failure,
	 *                  {@link com.shri.restinpeace.exception.RestInPeaceHttpException},
	 *                  {@link com.shri.restinpeace.exception.CircuitOpenException},
	 *                  {@link com.shri.restinpeace.exception.BulkheadFullException})
	 * @return the adapted value to return from the annotated method
	 */
	T adapt(CompletableFuture<Object> delegate);

}
