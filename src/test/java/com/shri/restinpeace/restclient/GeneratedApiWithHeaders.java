package com.shri.restinpeace.restclient;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.Headers;
import com.shri.restinpeace.annotation.request.PathParam;

/**
 * Otherwise-{@link GeneratedApi}-shaped, but with a {@code @Headers} method -
 * {@code RestClientProcessor} must still leave this to the reflective proxy
 * in its entirety, the same as {@code @HeaderParam}/{@code @HeaderMap}/etc.
 * already do. Regression coverage for a real bug: before this class existed,
 * a method combining the otherwise-supported shape with {@code @Headers} (or
 * {@code @ErrorType}) was silently included in the generated implementation,
 * which has no code path applying either annotation at all - see
 * {@code docs/design/compile-time-proxy-generation.md} §9.4.
 */
@RestClient
public interface GeneratedApiWithHeaders {

	@GET("http://localhost:{port}/items/{id}")
	@Headers({ "X-Trace: on" })
	String get(@PathParam("port") int port, @PathParam("id") String id);

}
