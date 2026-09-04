package com.shri.restinpeace.restclient;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.POST;
import com.shri.restinpeace.annotation.request.Multipart;
import com.shri.restinpeace.annotation.request.Part;
import com.shri.restinpeace.annotation.request.PathParam;

/**
 * Otherwise-supported shape, but {@code @Multipart}/{@code @Part} - not yet
 * covered by {@code RestClientProcessor} (a later step 2 slice). Regression
 * coverage that this still correctly falls back to the reflective proxy in
 * its entirety, the same way an unsupported feature always has.
 */
@RestClient
public interface GeneratedApiWithMultipart {

	@POST("http://localhost:{port}/items")
	@Multipart
	String post(@PathParam("port") int port, @Part("name") String name);

}
