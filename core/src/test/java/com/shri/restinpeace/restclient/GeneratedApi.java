package com.shri.restinpeace.restclient;

import java.io.File;
import java.util.Map;

import com.shri.restinpeace.RipResponse;
import com.shri.restinpeace.annotation.error.ErrorType;
import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.method.POST;
import com.shri.restinpeace.annotation.request.Body;
import com.shri.restinpeace.annotation.request.Destination;
import com.shri.restinpeace.annotation.request.HeaderMap;
import com.shri.restinpeace.annotation.request.HeaderParam;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.annotation.request.QueryMap;
import com.shri.restinpeace.annotation.request.QueryParam;
import com.shri.restinpeace.annotation.request.Url;
import com.shri.restinpeace.annotation.retry.Retry;
import com.shri.restinpeace.annotation.timeout.Timeout;
import com.shri.restinpeace.download.DownloadProgressListener;

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

	@GET("http://localhost:{port}/echo")
	String echo(@PathParam("port") int port, @HeaderParam("X-Fixed") String headerValue,
			@HeaderParam(value = "X-Default", defaultValue = "header-default") String headerDefault,
			@QueryParam(value = "q", required = true) String requiredQuery, @QueryMap Map<String, String> queryMap,
			@HeaderMap Map<String, String> headerMap);

	@POST("http://localhost:{port}/echo")
	String echoBody(@PathParam("port") int port, @Body String body);

	@GET
	String getByUrl(@Url String url);

	@GET("http://localhost:{port}/error")
	@ErrorType(ApiError.class)
	String getError(@PathParam("port") int port);

	@GET("http://localhost:{port}/binary")
	byte[] getBinary(@PathParam("port") int port);

	@GET("http://localhost:{port}/binary")
	File downloadBinary(@PathParam("port") int port, @Destination File target, DownloadProgressListener onProgress);

	@GET("http://localhost:{port}/items/{id}")
	RipResponse<String> getWithResponse(@PathParam("port") int port, @PathParam("id") String id);

	@GET("http://localhost:{port}/binary")
	RipResponse<byte[]> getBinaryWithResponse(@PathParam("port") int port);

}
