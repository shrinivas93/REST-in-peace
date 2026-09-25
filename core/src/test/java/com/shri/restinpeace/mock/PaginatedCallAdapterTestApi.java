package com.shri.restinpeace.mock;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.pagination.PaginationCursor;
import com.shri.restinpeace.annotation.pagination.Paginated;
import com.shri.restinpeace.annotation.request.QueryParam;

/**
 * A {@code @RestClient} interface exercising
 * {@link com.shri.restinpeace.PaginatedCallAdapter} dispatch (see
 * {@code docs/design/reactor-call-adapter.md} §7.2) via
 * {@link TestPaginatedCallAdapterFactory} - {@code TestBox<T>}'s type
 * argument disqualifies this method from compile-time codegen the same way
 * a raw {@code List<User>} already does (E9), so this exercises the
 * reflective dispatch path, where
 * {@link com.shri.restinpeace.PaginatedCallAdapter} resolution actually
 * lives.
 */
@RestClient
public interface PaginatedCallAdapterTestApi {

	// Declared as TestBox<Order>, not TestBox<List<Order>>, deliberately: the
	// pagination coordinator decodes each "orders" element using this method's
	// sole declared type argument as the item type, regardless of what
	// TestPaginatedCallAdapterFactory's adapter actually hands back at
	// runtime (a TestBox<List<Object>> boxing the whole first page) - so the
	// declared type here must stay Order for decoding to work, even though
	// callers then need an unchecked cast to use the real return shape.
	@GET("/orders")
	@Paginated(itemsField = "orders", pointerField = "next_cursor")
	TestBox<PaginationTestApi.Order> fluxOrders(@QueryParam("cursor") @PaginationCursor String cursor);

}
