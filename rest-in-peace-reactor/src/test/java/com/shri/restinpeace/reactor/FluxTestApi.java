package com.shri.restinpeace.reactor;

import reactor.core.publisher.Flux;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.pagination.PaginationCursor;
import com.shri.restinpeace.annotation.pagination.Paginated;
import com.shri.restinpeace.annotation.request.QueryParam;

/**
 * A {@code @RestClient} interface exercising both {@code Flux<T>} flavors
 * (§7.1/§7.2) against a real {@code MockRestServer} -
 * {@link FluxListCallAdapterFactory} for the plain, non-{@code @Paginated}
 * method, {@link FluxPaginatedCallAdapterFactory} for the {@code @Paginated}
 * one.
 */
@RestClient
public interface FluxTestApi {

	@GET("/orders")
	Flux<Order> listOrders();

	@GET("/orders")
	@Paginated(itemsField = "orders", pointerField = "next_cursor")
	Flux<Order> fluxOrders(@QueryParam("cursor") @PaginationCursor String cursor);

	final class Order {
		public String id;
	}

}
