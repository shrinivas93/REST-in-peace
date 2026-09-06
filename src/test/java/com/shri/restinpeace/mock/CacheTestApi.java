package com.shri.restinpeace.mock;

import java.util.concurrent.CompletableFuture;

import com.shri.restinpeace.annotation.cache.NoCache;
import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.HeaderParam;
import com.shri.restinpeace.annotation.request.PathParam;

/**
 * A minimal {@code @RestClient} interface for {@link ResponseCachingTest} -
 * within the compile-time generator's supported shape, so these tests
 * exercise response caching against the real compile-time-generated
 * dispatch path, not just the reflective one.
 */
@RestClient
public interface CacheTestApi {

	@GET("/items/{id}")
	String getItem(@PathParam("id") String id);

	@GET("/items/{id}")
	@NoCache
	String getItemNoCache(@PathParam("id") String id);

	@GET("/items/{id}")
	CompletableFuture<String> getItemAsync(@PathParam("id") String id);

	@GET("/localized/{id}")
	String getLocalizedItem(@PathParam("id") String id, @HeaderParam("Accept-Language") String language);

}
