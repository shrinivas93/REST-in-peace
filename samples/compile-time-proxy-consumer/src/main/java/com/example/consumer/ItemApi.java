package com.example.consumer;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.annotation.request.QueryParam;

/**
 * A plain consumer-side {@code @RestClient} interface, written exactly the
 * way any real user of the published library would - no knowledge of RIP's
 * internals needed. Its single method sits within the compile-time proxy
 * generation feature's supported shape (a fixed {@code GET}, only
 * {@code @PathParam}/plain {@code @QueryParam}, a {@code String} return
 * type), so RIP's annotation processor generates {@code ItemApi_RipImpl} for
 * it automatically - see {@link Main}.
 */
@RestClient
public interface ItemApi {

	@GET("http://localhost:{port}/items/{id}")
	String getItem(@PathParam("port") int port, @PathParam("id") String id, @QueryParam("verbose") Integer verbose);

}
