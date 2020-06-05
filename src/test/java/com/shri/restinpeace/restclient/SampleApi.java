package com.shri.restinpeace.restclient;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.method.PUT;
import com.shri.restinpeace.annotation.request.HeaderParam;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.annotation.request.QueryParam;
import com.shri.restinpeace.annotation.request.Url;

@RestClient
public interface SampleApi {

	@GET("https://enaxsbw8ux2bb.x.pipedream.net/{pathParam1}")
	String foo(@Url() String endpoint, @PathParam("pathParam1") String x,
			@QueryParam(value = "queryParam1", required = true) Integer y,
			@HeaderParam(value = "headerParam1", required = true, defaultValue = "Covid") String val);

	@Deprecated
	String bar(@Url() String endpoint, @PathParam("pathParam1") String x,
			@QueryParam(value = "queryParam1", required = true) Integer y,
			@HeaderParam(value = "headerParam1", required = true, defaultValue = "Covid") String val);

	@GET("https://enaxsbw8ux2bb.x.pipedream.net/{pathParam1}")
	@PUT("https://enaxsbw8ux2bb.x.pipedream.net/{pathParam1}")
	String foobar(@Url() String endpoint, @PathParam("pathParam1") String x,
			@QueryParam(value = "queryParam1", required = true) Integer y,
			@HeaderParam(value = "headerParam1", required = true, defaultValue = "Covid") String val);

}
