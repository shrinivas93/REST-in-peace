package com.example.consumer;

import java.util.List;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.PathParam;

/**
 * Uses a generic {@code List<String>} return type, outside the compile-time
 * generator's supported shape (see
 * {@code docs/design/compile-time-proxy-generation.md}) - so
 * {@code RIP.getClient(UnsupportedApi.class)} falls back to the same
 * reflective {@code java.lang.reflect.Proxy} every {@code @RestClient}
 * interface used before this feature existed. No
 * {@code UnsupportedApi_RipImpl} is generated for it at all, and the
 * fallback call still works correctly - see {@link Main}. (Earlier versions
 * of this sample used {@code @HeaderParam}, then {@code @Multipart}/
 * {@code @Part}, then {@code CompletableFuture}, but step 2 of the design
 * added support for all three - see
 * {@code docs/design/compile-time-proxy-generation.md} §9.4 onward - so this
 * moved to a feature that is permanently unsupported: a generic collection
 * return type isn't decodable via a single {@code Class<?>} literal the way
 * every supported return type is, with or without {@code CompletableFuture}
 * wrapping it, so it was never on the design doc's feature-parity table to
 * begin with.)
 */
@RestClient
public interface UnsupportedApi {

	@GET("http://localhost:{port}/items/{id}")
	List<String> getItem(@PathParam("port") int port, @PathParam("id") String id);

}
