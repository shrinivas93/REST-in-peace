package com.shri.restinpeace.mock;

import java.util.Iterator;
import java.util.Map;
import java.util.stream.Stream;

import com.shri.restinpeace.Page;
import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.method.POST;
import com.shri.restinpeace.annotation.pagination.PaginationAdvance;
import com.shri.restinpeace.annotation.pagination.PaginationCursor;
import com.shri.restinpeace.annotation.pagination.PaginationSignalSource;
import com.shri.restinpeace.annotation.pagination.Paginated;
import com.shri.restinpeace.annotation.pagination.PointerKind;
import com.shri.restinpeace.annotation.request.Body;
import com.shri.restinpeace.annotation.request.QueryParam;

/**
 * A {@code @Paginated} test fixture against a real {@link MockRestServer} -
 * covers a {@code VALUE} query-param cursor, a {@code FULL_URL} pointer, the
 * {@code hasMore}/{@code total} termination signals (§6.5 of
 * {@code docs/design/pagination-helper.md}), and the {@code Page<T>}/
 * {@code Stream<T>}/{@code Iterator<T>} return-type-driven iteration styles
 * (§6.4).
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

	@GET("/orders")
	@Paginated(itemsField = "data.orders", pointerField = "next_cursor")
	Page<Order> listOrdersByNestedItemsField(@QueryParam("cursor") @PaginationCursor String cursor);

	@GET("/orders")
	@Paginated(itemsField = "orders", pointerField = "next_seq")
	Page<Order> listOrdersByLongCursor(@QueryParam("seq") @PaginationCursor long seq);

	@GET("/orders")
	@Paginated(itemsField = "orders", pointerField = "next_cursor")
	Stream<Order> streamOrders(@QueryParam("cursor") @PaginationCursor String cursor);

	@GET("/orders")
	@Paginated(itemsField = "orders", pointerField = "next_cursor")
	Iterator<Order> iterateOrders(@QueryParam("cursor") @PaginationCursor String cursor);

	@POST("/orders/search")
	@Paginated(itemsField = "orders", pointerField = "next_cursor")
	Page<Order> searchOrders(@Body @PaginationCursor(bodyField = "cursor") Map<String, Object> body);

	@POST("/orders/search")
	@Paginated(itemsField = "orders", pointerField = "next_cursor")
	Page<Order> searchOrdersByNestedBodyField(
			@Body @PaginationCursor(bodyField = "meta.cursor") Map<String, Object> body);

	@GET("/orders")
	@Paginated(itemsField = "orders", pointerSource = PaginationSignalSource.ITEM_FIELD, pointerField = "id")
	Page<Order> listOrdersByItemFieldCursor(@QueryParam("since") @PaginationCursor String since);

	@GET("/orders")
	@Paginated(itemsField = "orders", pointerSource = PaginationSignalSource.ITEM_FIELD,
			pointerField = "id,createdAt")
	Page<Order> listOrdersByCompositeItemFieldCursor(@QueryParam("lastId") @PaginationCursor String lastId,
			@QueryParam("lastTs") @PaginationCursor String lastTimestamp);

	@POST("/orders/search")
	@Paginated(itemsField = "orders", pointerSource = PaginationSignalSource.ITEM_FIELD,
			pointerField = "id,createdAt")
	Page<Order> searchOrdersByCompositeItemFieldIntoBody(
			@Body @PaginationCursor(bodyField = "lastId,lastTimestamp") Map<String, Object> body);

	@GET("/orders")
	@Paginated(itemsField = "orders", hasMoreSource = PaginationSignalSource.RESPONSE_BODY,
			hasMoreField = "has_more", pointerSource = PaginationSignalSource.ITEM_FIELD, pointerField = "id")
	Page<Order> listOrdersByItemFieldWithHasMore(@QueryParam("since") @PaginationCursor String since);

	@GET("/orders")
	@Paginated(itemsField = "", pointerKind = PointerKind.FULL_URL,
			pointerSource = PaginationSignalSource.RESPONSE_HEADER, pointerField = "Link")
	Page<Order> listOrdersByLinkHeader();

	@GET("/orders")
	@Paginated(itemsField = "orders", pointerSource = PaginationSignalSource.NONE,
			advance = PaginationAdvance.INCREMENT_BY_PAGE_SIZE, pageSize = 2,
			totalSource = PaginationSignalSource.RESPONSE_BODY, totalField = "total")
	Page<Order> listOrdersByOffsetWithTotal(@QueryParam("offset") @PaginationCursor int offset);

	@GET("/orders")
	@Paginated(itemsField = "orders", pointerSource = PaginationSignalSource.NONE,
			advance = PaginationAdvance.INCREMENT_BY_ONE,
			totalPagesSource = PaginationSignalSource.RESPONSE_BODY, totalPagesField = "totalPages")
	Page<Order> listOrdersByPageNumberWithTotalPages(@QueryParam("page") @PaginationCursor int page);

	@GET("/orders")
	@Paginated(itemsField = "orders", pointerSource = PaginationSignalSource.NONE,
			advance = PaginationAdvance.INCREMENT_BY_PAGE_SIZE, pageSize = 2)
	Page<Order> listOrdersByOffsetShortPageStop(@QueryParam("offset") @PaginationCursor int offset);

	@GET("/orders")
	@Paginated(itemsField = "orders", pointerSource = PaginationSignalSource.NONE,
			advance = PaginationAdvance.INCREMENT_BY_ONE)
	Page<Order> listOrdersByPageNumberNoSignal(@QueryParam("page") @PaginationCursor int page);

	@POST("/orders/search")
	@Paginated(itemsField = "orders", pointerSource = PaginationSignalSource.NONE,
			advance = PaginationAdvance.INCREMENT_BY_PAGE_SIZE, pageSize = 2)
	Page<Order> searchOrdersByOffsetIntoBody(@Body @PaginationCursor(bodyField = "offset") Map<String, Object> body);

	final class Order {
		public String id;
		public String createdAt;
	}

}
