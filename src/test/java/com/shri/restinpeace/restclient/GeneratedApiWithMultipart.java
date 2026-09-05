package com.shri.restinpeace.restclient;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.POST;
import com.shri.restinpeace.annotation.request.Multipart;
import com.shri.restinpeace.annotation.request.Part;
import com.shri.restinpeace.annotation.request.PathParam;

/**
 * {@code @Multipart}/{@code @Part} - {@code RestClientProcessor} now
 * genuinely generates for this (step 2), building the multipart body via
 * {@code RestRequestProcessor.beginGeneratedMultipart}/
 * {@code applyPartValue}. Originally added as regression coverage proving
 * this still fell back to the reflective proxy; kept under this name as a
 * positive test now that {@code @Multipart} is supported.
 */
@RestClient
public interface GeneratedApiWithMultipart {

	@POST("http://localhost:{port}/echo")
	@Multipart
	String post(@PathParam("port") int port, @Part("name") String name);

}
