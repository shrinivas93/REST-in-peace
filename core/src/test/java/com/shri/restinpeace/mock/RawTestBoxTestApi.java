package com.shri.restinpeace.mock;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.PathParam;

/**
 * A separate, minimal {@code @RestClient} interface (kept apart from
 * {@link CallAdapterTestApi} for the same reason {@link CallAdapterMonoTestApi}
 * is) returning a raw {@link TestBox} - no type parameter - so a registered
 * {@link TestCallAdapterFactory} sees a return type it can't extract an
 * inner type from. Proves {@link TestCallAdapterFactory#get} declines this
 * shape instead of throwing a {@code ClassCastException} that would abort
 * validation for the whole interface.
 */
@RestClient
@SuppressWarnings("rawtypes")
public interface RawTestBoxTestApi {

	@GET("/orders/{id}")
	TestBox getOrder(@PathParam("id") String id);

}
