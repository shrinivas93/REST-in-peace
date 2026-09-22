package com.shri.restinpeace;

import java.util.Optional;

/**
 * The programmatic escape hatch for a pagination shape {@code @Paginated}'s
 * closed annotation vocabulary can't express - see
 * {@code docs/design/pagination-helper.md} §6.8 and §10 for exactly which
 * cases require it. Recognized by declared parameter type on a
 * {@code Page<T>}/{@code Stream<T>}/{@code Iterator<T>}-returning method, the
 * same idiom RIP already uses for {@code CompletableFuture<T>}/
 * {@code RipResponse<T>} return types - no marker annotation needed:
 *
 * <pre>
 * {@literal @}GET("/orders")
 * Page{@literal <}Order{@literal >} listOrders({@literal @}QueryParam("status") String status,
 *         PaginationStrategy{@literal <}Order{@literal >} strategy);
 * </pre>
 *
 * <p>
 * {@code @Paginated} and a {@code PaginationStrategy<T>} parameter are
 * mutually exclusive on one method - combining them is a validation error,
 * the same "config vs. provider, pick one" precedent as
 * {@link CircuitBreakerConfig}/{@link CircuitBreakerProvider}.
 *
 * @param <T> the decoded item type - must match the method's
 *            {@code Page<T>}/{@code Stream<T>}/{@code Iterator<T>} return
 *            type's own type argument
 */
@FunctionalInterface
public interface PaginationStrategy<T> {

	/**
	 * Consulted after every page fetch, including the first, to decide
	 * whether to continue and what the next request should look like. The
	 * lambda only answers that question - the actual re-invocation, and
	 * everything that comes with it (retry, cache, circuit breaker,
	 * bulkhead, interceptors), stays owned by the same coordinator the
	 * declarative {@code @Paginated} path uses (§6.9).
	 *
	 * @param context this page's items and metadata
	 * @return the next request to issue, or {@link Optional#empty()} if this
	 *         was the last page
	 */
	Optional<PaginationRequest> nextRequest(PaginationContext<T> context);

}
