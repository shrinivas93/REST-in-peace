package com.shri.restinpeace.reactor;

import reactor.core.publisher.Mono;

import com.shri.restinpeace.RipResponse;
import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.method.POST;
import com.shri.restinpeace.annotation.request.Body;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.annotation.retry.Retry;

/**
 * A {@code @RestClient} interface exercising {@link MonoCallAdapterFactory}
 * dispatch against a real {@code MockRestServer} - {@code Mono<T>}'s type
 * argument disqualifies every method here from compile-time codegen the
 * same way a raw {@code List<User>} already does (E9), so these tests
 * exercise the reflective dispatch path, where
 * {@link com.shri.restinpeace.CallAdapter} resolution actually lives.
 */
@RestClient
public interface ReactorTestApi {

	@GET("/orders/{id}")
	Mono<String> getOrder(@PathParam("id") String id);

	@GET("/orders/{id}")
	Mono<RipResponse<String>> getOrderWithResponse(@PathParam("id") String id);

	@GET("/reports/{id}")
	Mono<byte[]> downloadReport(@PathParam("id") String id);

	@POST("/events")
	Mono<Void> fireEvent(@Body String payload);

	@POST("/orders")
	@Retry(times = 3, retryOnStatus = { 503 }, delayMillis = 1)
	Mono<String> createOrderWithRetry(@Body String payload);

}
