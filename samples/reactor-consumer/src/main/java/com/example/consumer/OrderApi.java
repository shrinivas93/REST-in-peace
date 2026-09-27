package com.example.consumer;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.pagination.PaginationCursor;
import com.shri.restinpeace.annotation.pagination.Paginated;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.annotation.request.QueryParam;

/**
 * A plain {@code @RestClient} interface exercising every {@code Mono<T>}/
 * {@code Flux<T>} shape {@code rest-in-peace-reactor} supports (see
 * {@code docs/design/reactor-call-adapter.md} §6/§7): a single-response
 * {@code Mono<T>}, a plain {@code Flux<T>} flattening one JSON array
 * response (§7.1), and a {@code @Paginated Flux<T>} auto-flattening every
 * page with real backpressure (§7.2).
 */
@RestClient
public interface OrderApi {

	@GET("/orders/{id}")
	Mono<Order> getOrder(@PathParam("id") String id);

	@GET("/orders")
	Flux<Order> listOrders();

	@GET("/orders/paged")
	@Paginated(itemsField = "orders", pointerField = "next")
	Flux<Order> streamAllOrders(@QueryParam("cursor") @PaginationCursor String cursor);

	final class Order {
		public String id;
	}

}
