package com.shri.restinpeace.restclient;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.annotation.request.QueryParam;
import com.shri.restinpeace.annotation.retry.Retry;
import com.shri.restinpeace.annotation.timeout.Timeout;

/**
 * A top-level, fully-supported {@code @RestClient} interface used to verify
 * {@code RestClientProcessor} actually generates {@code GeneratedApi_RipImpl}
 * and that {@code RIP.getClient(...)} picks it over the reflective proxy -
 * see {@code docs/design/compile-time-proxy-generation.md}. Deliberately
 * top-level (not nested, unlike every other test API in this module), since
 * the processor doesn't support a nested/private interface yet.
 */
@RestClient
public interface GeneratedApi {

	@GET("http://localhost:{port}/items/{id}")
	String get(@PathParam("port") int port, @PathParam("id") String id, @QueryParam("q") Integer q);

	@GET("http://localhost:{port}/slow")
	@Timeout(readMillis = 50)
	String getSlowWithShortTimeout(@PathParam("port") int port);

	@GET("http://localhost:{port}/flaky")
	@Retry(times = 3, delayMillis = 5, retryOnStatus = { 503 })
	String getFlaky(@PathParam("port") int port);

}
