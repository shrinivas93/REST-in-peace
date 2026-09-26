package com.shri.restinpeace.mock;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import com.shri.restinpeace.Page;
import com.shri.restinpeace.PaginatedCallAdapter;
import com.shri.restinpeace.PaginatedCallAdapterFactory;

/**
 * Adapts any {@code @Paginated} method returning {@link TestBox}{@code <T>}
 * - flattens just the first page's items into a {@code List<Object>}
 * (proving the dispatch/registry wiring is what's under test here, not a
 * real multi-page-walking adapter; that behavior is already exhaustively
 * covered by {@code rest-in-peace-reactor}'s own real
 * {@code FluxPaginatedCallAdapterFactory} tests). See
 * {@code docs/design/reactor-call-adapter.md} §7.2.
 */
final class TestPaginatedCallAdapterFactory implements PaginatedCallAdapterFactory {

	static final TestPaginatedCallAdapterFactory INSTANCE = new TestPaginatedCallAdapterFactory();

	@Override
	public Optional<PaginatedCallAdapter<?>> get(Method method) {
		if (method.getReturnType() != TestBox.class) {
			return Optional.empty();
		}
		return Optional.of((PaginatedCallAdapter<TestBox<List<Object>>>) firstPageSupplier -> {
			Page<Object> firstPage = firstPageSupplier.get();
			return TestBox.of(firstPage.items());
		});
	}

}
