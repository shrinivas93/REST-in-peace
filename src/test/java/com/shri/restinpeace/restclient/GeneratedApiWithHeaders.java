package com.shri.restinpeace.restclient;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.Headers;
import com.shri.restinpeace.annotation.request.PathParam;

/**
 * Otherwise-{@link GeneratedApi}-shaped, with a {@code @Headers} method -
 * {@code RestClientProcessor} now genuinely generates for this (step 2),
 * applying the fixed header via
 * {@code RestRequestProcessor.applyGeneratedHeaders}. Originally added as
 * regression coverage for a real bug in step 1, where a method combining the
 * otherwise-supported shape with {@code @Headers} (or {@code @ErrorType})
 * was silently included in the generated implementation despite neither
 * being applied at all - see
 * {@code docs/design/compile-time-proxy-generation.md} §9.4. Kept under this
 * name as a positive test for {@code @Headers} support now that it exists.
 */
@RestClient
public interface GeneratedApiWithHeaders {

	@GET("http://localhost:{port}/items/{id}")
	@Headers({ "X-Trace: on" })
	String get(@PathParam("port") int port, @PathParam("id") String id);

}
