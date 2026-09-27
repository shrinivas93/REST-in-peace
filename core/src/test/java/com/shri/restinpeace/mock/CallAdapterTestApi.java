package com.shri.restinpeace.mock;

import com.shri.restinpeace.RipResponse;
import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.method.POST;
import com.shri.restinpeace.annotation.request.Body;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.annotation.retry.Retry;

/**
 * A {@code @RestClient} interface exercising {@link com.shri.restinpeace.CallAdapter}
 * dispatch (see {@code docs/design/reactor-call-adapter.md} §8.1) via
 * {@link TestCallAdapterFactory} - {@code TestBox<T>}'s type argument
 * disqualifies every method here from compile-time codegen the same way a
 * raw {@code List<User>} already does (E9), so these tests exercise the
 * reflective dispatch path, where {@link com.shri.restinpeace.CallAdapter}
 * resolution actually lives.
 */
@RestClient
public interface CallAdapterTestApi {

	@GET("/orders/{id}")
	TestBox<String> getOrder(@PathParam("id") String id);

	@GET("/orders/{id}")
	TestBox<RipResponse<String>> getOrderWithResponse(@PathParam("id") String id);

	@POST("/orders")
	@Retry(times = 3, retryOnStatus = { 503 }, delayMillis = 1)
	TestBox<String> createOrderWithRetry(@Body String payload);

}
