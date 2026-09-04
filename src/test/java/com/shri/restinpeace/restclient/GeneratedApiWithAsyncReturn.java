package com.shri.restinpeace.restclient;

import java.util.concurrent.CompletableFuture;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.PathParam;

/**
 * Otherwise-{@link GeneratedApi}-shaped, with a {@code CompletableFuture}
 * return type - {@code RestClientProcessor} now genuinely generates for
 * this (step 2's final slice), applying the same
 * {@code executeAsyncWithRetry}/{@code decodeOrThrow} machinery the
 * reflective path's own {@code processAsync} uses via
 * {@code RestRequestProcessor.finishGeneratedAsync}. Originally added as
 * regression coverage proving this still fell back to the reflective
 * proxy; kept under this name as a positive test now that
 * {@code CompletableFuture} is supported - the last item in the design
 * doc's §5 feature table.
 */
@RestClient
public interface GeneratedApiWithAsyncReturn {

	@GET("http://localhost:{port}/items/{id}")
	CompletableFuture<String> get(@PathParam("port") int port, @PathParam("id") String id);

}
