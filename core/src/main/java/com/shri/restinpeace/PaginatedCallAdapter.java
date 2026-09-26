package com.shri.restinpeace;

import java.util.function.Supplier;

/**
 * Adapts a {@code @Paginated} method's page sequence into a consumer-chosen
 * return type {@code T} - the pagination-aware counterpart of
 * {@link CallAdapter}, for a return type built around fetching more than one
 * page over time (e.g. Project Reactor's {@code Flux<T>}, via the separate
 * {@code rest-in-peace-reactor} module) rather than a single response.
 * Registered via
 * {@link RIP#addPaginatedCallAdapterFactory(PaginatedCallAdapterFactory)}.
 * See {@code docs/design/reactor-call-adapter.md} §7.2.
 *
 * <p>
 * Like {@link CallAdapter}, an adapter never dispatches its own HTTP call -
 * {@link #adapt} only ever consumes the {@code firstPageSupplier} RIP's own
 * {@code @Paginated} dispatch already built, guaranteeing every page fetch
 * still goes through the identical pipeline (retry, cache, circuit breaker,
 * bulkhead, interceptors) as a hand-written call to the same method - the
 * same guarantee {@code Page<T>}/{@code Stream<T>}/{@code Iterator<T>}'s own
 * page-at-a-time iteration already has.
 *
 * @param <T> the adapted return type this instance produces
 */
public interface PaginatedCallAdapter<T> {

	/**
	 * Adapts a {@code @Paginated} method's page sequence into {@code T}.
	 *
	 * @param firstPageSupplier fetches the first page, called at most once -
	 *                          {@link Page#next()} on the result (and on
	 *                          every page after it) fetches the next one,
	 *                          through the exact same pipeline, exactly as
	 *                          {@code Page<T>}'s own consumer-driven
	 *                          iteration does
	 * @return the adapted value to return from the annotated method
	 */
	T adapt(Supplier<Page<Object>> firstPageSupplier);

}
