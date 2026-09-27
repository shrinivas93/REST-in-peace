package com.shri.restinpeace.restclient;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.PathParam;

/**
 * A plain, non-generic-return method - otherwise identical in shape to
 * {@link GeneratedApi#get}, so it's still fully codegen-eligible - used to
 * regression-test that a {@link com.shri.restinpeace.CallAdapterFactory}
 * claiming a PLAIN-classified method's return type is honored by generated
 * dispatch instead of being silently bypassed. See
 * {@code docs/design/reactor-call-adapter.md} and
 * {@code AdapterAwareGeneratedApiTest}.
 */
@RestClient
public interface AdapterEligiblePlainApi {

	@GET("http://localhost:{port}/items/{id}")
	String get(@PathParam("port") int port, @PathParam("id") String id);

}
