package com.shri.restinpeace.mock;

import reactor.core.publisher.Mono;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.PathParam;

/**
 * A separate, minimal {@code @RestClient} interface (kept apart from
 * {@link CallAdapterTestApi} so its one method's validation doesn't affect
 * every other test in {@code CallAdapterIntegrationTest}) returning the
 * real denylisted-by-name {@code reactor.core.publisher.Mono} (a
 * test-only stand-in, see that class's own javadoc) - proves removing the
 * only claiming {@link com.shri.restinpeace.CallAdapterFactory} reverts
 * this method to the §8.3/§8.4 validation rejection.
 */
@RestClient
public interface CallAdapterMonoTestApi {

	@GET("/orders/{id}")
	Mono<String> getOrderAsMono(@PathParam("id") String id);

}
