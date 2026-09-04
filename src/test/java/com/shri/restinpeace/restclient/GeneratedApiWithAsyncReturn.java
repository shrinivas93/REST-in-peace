package com.shri.restinpeace.restclient;

import java.util.concurrent.CompletableFuture;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.PathParam;

/**
 * Otherwise-{@link GeneratedApi}-shaped, but a {@code CompletableFuture}
 * return type - not yet covered by {@code RestClientProcessor} (a later
 * step 2 slice, along with {@code byte[]}/{@code File}/{@code RipResponse}).
 * Regression coverage that this still correctly falls back to the
 * reflective proxy in its entirety, the same way an unsupported feature
 * always has.
 */
@RestClient
public interface GeneratedApiWithAsyncReturn {

	@GET("http://localhost:{port}/items/{id}")
	CompletableFuture<String> get(@PathParam("port") int port, @PathParam("id") String id);

}
