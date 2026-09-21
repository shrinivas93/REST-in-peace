package com.shri.restinpeace.mock;

import com.shri.restinpeace.Page;
import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.pagination.PaginationCursor;
import com.shri.restinpeace.annotation.pagination.PaginationSignalSource;
import com.shri.restinpeace.annotation.pagination.Paginated;
import com.shri.restinpeace.annotation.pagination.PointerKind;
import com.shri.restinpeace.annotation.request.QueryParam;

/**
 * A {@code @Paginated} test fixture against a real {@link MockRestServer} -
 * covers the chunk-2-supported shapes: a {@code VALUE} query-param cursor,
 * a {@code FULL_URL} pointer, and the {@code hasMore}/{@code total}
 * termination signals (§6.5 of {@code docs/design/pagination-helper.md}).
 */
@RestClient
public interface PaginationTestApi {

	@GET("/orders")
	@Paginated(itemsField = "orders", pointerField = "next_cursor")
	Page<Order> listOrdersByCursor(@QueryParam("cursor") @PaginationCursor String cursor);

	@GET("/orders")
	@Paginated(itemsField = "orders", pointerKind = PointerKind.FULL_URL, pointerField = "next")
	Page<Order> listOrdersByFullUrl();

	@GET("/orders")
	@Paginated(itemsField = "orders", pointerField = "next_cursor",
			hasMoreSource = PaginationSignalSource.RESPONSE_BODY, hasMoreField = "has_more")
	Page<Order> listOrdersWithHasMore(@QueryParam("cursor") @PaginationCursor String cursor);

	@GET("/orders")
	@Paginated(itemsField = "orders", pointerField = "next_cursor",
			totalSource = PaginationSignalSource.RESPONSE_HEADER, totalField = "X-Total-Count")
	Page<Order> listOrdersWithTotalHeader(@QueryParam("cursor") @PaginationCursor String cursor);

	@GET("/orders")
	@Paginated(itemsField = "orders", pointerField = "next_page")
	Page<Order> listOrdersByIntCursor(@QueryParam("page") @PaginationCursor int page);

	final class Order {
		public String id;
	}

}
