package com.example.consumer;

import java.util.concurrent.CompletableFuture;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.PathParam;

/**
 * Uses a {@code CompletableFuture} return type, outside the compile-time
 * generator's supported shape (see
 * {@code docs/design/compile-time-proxy-generation.md}) - so
 * {@code RIP.getClient(UnsupportedApi.class)} falls back to the same
 * reflective {@code java.lang.reflect.Proxy} every {@code @RestClient}
 * interface used before this feature existed. No
 * {@code UnsupportedApi_RipImpl} is generated for it at all, and the
 * fallback call still works correctly - see {@link Main}. (Earlier versions
 * of this sample used {@code @HeaderParam}, then {@code @Multipart}/
 * {@code @Part} here, but step 2 of the design added support for both - see
 * {@code docs/design/compile-time-proxy-generation.md} §9.4 onward - so this
 * moved to a feature still genuinely unsupported: return-type expansion is
 * a later step 2 slice.)
 */
@RestClient
public interface UnsupportedApi {

	@GET("http://localhost:{port}/items/{id}")
	CompletableFuture<String> getItem(@PathParam("port") int port, @PathParam("id") String id);

}
