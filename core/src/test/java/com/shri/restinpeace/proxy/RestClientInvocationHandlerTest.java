package com.shri.restinpeace.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shri.restinpeace.annotation.marker.RestClient;
import com.shri.restinpeace.annotation.method.GET;
import com.shri.restinpeace.annotation.request.PathParam;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.mock.MockResponse;
import com.shri.restinpeace.mock.MockRestServer;

/**
 * {@link RestClientInvocationHandler}'s no-arg constructor - never exercised
 * via {@code RIP.getClient(...)}, which always supplies an explicit base URL,
 * {@code RipClientConfig}, or {@code RequestExecutor} to one of the other
 * constructors instead.
 */
class RestClientInvocationHandlerTest {

	@RestClient
	private interface NoArgHandlerApi {
		@GET("http://localhost:{port}/orders/{id}")
		String getOrder(@PathParam("port") int port, @PathParam("id") String id);
	}

	private MockRestServer server;

	@BeforeEach
	void setUp() {
		server = MockRestServer.start();
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	@Test
	void noArgConstructor_behavesLikeANullBaseUrlOverride() {
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("confirmed"));
		int port = Integer.parseInt(server.baseUrl().substring(server.baseUrl().lastIndexOf(':') + 1));
		NoArgHandlerApi api = (NoArgHandlerApi) Proxy.newProxyInstance(NoArgHandlerApi.class.getClassLoader(),
				new Class<?>[] { NoArgHandlerApi.class }, new RestClientInvocationHandler());

		assertEquals("confirmed", api.getOrder(port, "42"));
	}

}
