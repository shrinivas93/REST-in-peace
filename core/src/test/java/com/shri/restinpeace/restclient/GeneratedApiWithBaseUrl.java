package com.shri.restinpeace.restclient;

import com.shri.restinpeace.annotation.marker.BaseUrl;
import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.PathParam;

/**
 * A top-level, {@code @BaseUrl}-only {@code @RestClient} interface - every
 * other generated-code test API in this package uses a fully absolute method
 * URL, so {@code RestClientProcessor}'s own {@code @BaseUrl}-without-a-
 * runtime-override fallback (and the null-{@code @PathParam} throw on that
 * same generated path) is otherwise never exercised. Deliberately points at
 * {@code localhost:1} (the same "always refuses the connection" convention
 * {@code AbstractRipIntegrationTest.LocalApi#getUnreachableWithRetry} uses) -
 * a test only needs URL resolution to run, not an actual response.
 */
@RestClient
@BaseUrl("http://localhost:1")
public interface GeneratedApiWithBaseUrl {

	@GET("/items/{id}")
	String getItem(@PathParam("id") String id);

}
