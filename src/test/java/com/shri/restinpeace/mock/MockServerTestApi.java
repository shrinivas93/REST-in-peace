package com.shri.restinpeace.mock;

import java.util.List;
import java.util.Map;

import com.shri.restinpeace.RipResponse;
import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.method.POST;
import com.shri.restinpeace.annotation.request.Body;
import com.shri.restinpeace.annotation.request.Field;
import com.shri.restinpeace.annotation.request.FieldMap;
import com.shri.restinpeace.annotation.request.FormUrlEncoded;
import com.shri.restinpeace.annotation.request.HeaderParam;
import com.shri.restinpeace.annotation.request.Multipart;
import com.shri.restinpeace.annotation.request.Part;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.annotation.request.QueryParam;
import com.shri.restinpeace.annotation.retry.Retry;
import com.shri.restinpeace.annotation.timeout.Timeout;

/**
 * A minimal {@code @RestClient} interface for {@link MockRestServerTest} -
 * within the compile-time generator's supported shape, so these tests
 * exercise {@link MockRestServer} against the real compile-time-generated
 * dispatch path, not just the reflective one.
 */
@RestClient
public interface MockServerTestApi {

	@GET("/orders/{id}")
	String getOrder(@PathParam("id") String id,
			@QueryParam(value = "verbose", required = false, defaultValue = "false") String verbose);

	@POST("/orders")
	@Retry(delayMillis = 1)
	String createOrder(@Body String payload);

	@GET("/secure")
	String getSecure(@HeaderParam("Authorization") String token);

	@GET("/with-headers")
	RipResponse<String> getWithHeaders();

	@GET("/orders/{id}")
	@Timeout(readMillis = 100)
	String getOrderWithShortTimeout(@PathParam("id") String id);

	@POST("/upload")
	@Multipart
	String upload(@Part("caption") String caption, @Part(value = "file", fileName = "data.bin") byte[] file);

	@POST("/orders")
	@Retry(times = 3, delayMillis = 100, backoffMultiplier = 3.0, retryOnStatus = { 503 })
	String createOrderWithBackoff(@Body String payload);

	@POST("/oauth/token")
	@FormUrlEncoded
	String getToken(@Field("grant_type") String grantType, @Field("client_id") String clientId);

	@POST("/search")
	@FormUrlEncoded
	String searchForm(@FieldMap Map<String, Object> filters);

	@POST("/tags")
	@FormUrlEncoded
	String submitTags(@Field("tag") List<String> tags);

}
