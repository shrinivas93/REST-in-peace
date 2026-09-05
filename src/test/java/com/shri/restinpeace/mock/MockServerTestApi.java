package com.shri.restinpeace.mock;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.method.POST;
import com.shri.restinpeace.annotation.request.Body;
import com.shri.restinpeace.annotation.request.HeaderParam;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.annotation.request.QueryParam;
import com.shri.restinpeace.annotation.retry.Retry;

/**
 * A minimal {@code @RestClient} interface for {@link MockRestServerTest} -
 * within the compile-time generator's supported shape, so these tests
 * exercise {@link MockRestServer} against the real compile-time-generated
 * dispatch path, not just the reflective one.
 */
@RestClient
public interface MockServerTestApi {

	@GET("/orders/{id}")
	String getOrder(@PathParam("id") String id,
			@QueryParam(value = "verbose", required = false, defaultValue = "false") String verbose);

	@POST("/orders")
	@Retry(delayMillis = 1)
	String createOrder(@Body String payload);

	@GET("/secure")
	String getSecure(@HeaderParam("Authorization") String token);

}
