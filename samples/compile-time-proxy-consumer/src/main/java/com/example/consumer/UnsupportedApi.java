package com.example.consumer;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.POST;
import com.shri.restinpeace.annotation.request.Multipart;
import com.shri.restinpeace.annotation.request.Part;
import com.shri.restinpeace.annotation.request.PathParam;

/**
 * Uses {@code @Multipart}/{@code @Part}, outside the compile-time
 * generator's supported shape (see
 * {@code docs/design/compile-time-proxy-generation.md}) - so
 * {@code RIP.getClient(UnsupportedApi.class)} falls back to the same
 * reflective {@code java.lang.reflect.Proxy} every {@code @RestClient}
 * interface used before this feature existed. No
 * {@code UnsupportedApi_RipImpl} is generated for it at all, and the
 * fallback call still works correctly - see {@link Main}. (Earlier versions
 * of this sample used {@code @HeaderParam} here, but step 2 of the design
 * added support for that - see the design doc's §9.4 onward - so this had
 * to move to a feature still genuinely unsupported.)
 */
@RestClient
public interface UnsupportedApi {

	@POST("http://localhost:{port}/items/{id}")
	@Multipart
	String postItem(@PathParam("port") int port, @PathParam("id") String id, @Part("name") String name);

}
