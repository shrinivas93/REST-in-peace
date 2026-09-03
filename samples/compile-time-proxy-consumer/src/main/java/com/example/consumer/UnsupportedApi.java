package com.example.consumer;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.HeaderParam;
import com.shri.restinpeace.annotation.request.PathParam;

/**
 * Uses {@code @HeaderParam}, outside the compile-time generator's supported
 * shape (see {@code docs/design/compile-time-proxy-generation.md}) - so
 * {@code RIP.getClient(UnsupportedApi.class)} falls back to the same
 * reflective {@code java.lang.reflect.Proxy} every {@code @RestClient}
 * interface used before this feature existed. No
 * {@code UnsupportedApi_RipImpl} is generated for it at all, and the
 * fallback call still works correctly - see {@link Main}.
 */
@RestClient
public interface UnsupportedApi {

	@GET("http://localhost:{port}/items/{id}")
	String getItem(@PathParam("port") int port, @PathParam("id") String id, @HeaderParam("X-Trace") String trace);

}
