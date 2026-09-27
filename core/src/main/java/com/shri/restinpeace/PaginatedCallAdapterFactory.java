package com.shri.restinpeace;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Recognizes a {@code @Paginated} method's return type and produces a
 * {@link PaginatedCallAdapter} for it - the pagination-aware counterpart of
 * {@link CallAdapterFactory}, consulted only for a method actually annotated
 * {@code @Paginated} (never for a {@code PaginationStrategy<T>}-parameter
 * method, and never for a plain, non-paginated call - see
 * {@link CallAdapterFactory} for that). See
 * {@code docs/design/reactor-call-adapter.md} §7.2/§7.3.
 */
public interface PaginatedCallAdapterFactory {

	/**
	 * @param method the {@code @Paginated} method being dispatched or
	 *               validated
	 * @return an adapter for this method's return type, or
	 *         {@link Optional#empty()} to decline (letting the next
	 *         registered factory, or the built-in {@code Page<T>} fallback,
	 *         handle it)
	 */
	Optional<PaginatedCallAdapter<?>> get(Method method);

}
