package com.example.consumer;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.PathParam;

/**
 * The one {@code @RestClient} interface this sample registers via
 * {@code @EnableRestInPeaceClients} - {@code baseUrlProperty} names the
 * {@code user-api.base-url} key {@code application.yml} sets, resolved by
 * the starter from Spring's own {@code Environment} at bean-registration
 * time, with zero hand-written {@code @Bean} method.
 */
@RestClient(baseUrlProperty = "user-api.base-url")
public interface UserApi {

	@GET("/users/{id}")
	String getUser(@PathParam("id") String id);

}
