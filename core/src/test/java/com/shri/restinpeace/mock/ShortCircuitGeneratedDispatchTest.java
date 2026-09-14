package com.shri.restinpeace.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import kong.unirest.JsonObjectMapper;

import com.shri.restinpeace.RIP;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.interceptor.RequestContext;
import com.shri.restinpeace.interceptor.RequestInterceptor;
import com.shri.restinpeace.interceptor.ShortCircuitResponse;

/**
 * {@code RequestInterceptor#shortCircuit} against {@link MockServerTestApi},
 * confirmed elsewhere (see its own javadoc) to exercise the real
 * compile-time-generated dispatch path ({@code RequestExecutor}'s
 * {@code finishGenerated*} methods) rather than the reflective proxy - so a
 * passing test here proves short-circuiting is wired into that path too, not
 * just the reflective one.
 */
class ShortCircuitGeneratedDispatchTest {

	private MockRestServer server;

	@BeforeEach
	void setUp() {
		RIP.setObjectMapper(new JsonObjectMapper());
		server = MockRestServer.start();
	}

	@AfterEach
	void tearDown() {
		RIP.clearInterceptors();
		server.close();
	}

	@Test
	void shortCircuit_skipsTheNetworkCall_onTheGeneratedDispatchPath() {
		RIP.addInterceptor(new RequestInterceptor() {
			@Override
			public ShortCircuitResponse shortCircuit(RequestContext context) {
				return ShortCircuitResponse.ok("{\"status\":\"SHORT_CIRCUITED\"}");
			}
		});
		// No route registered at all - if this reached the network, it would
		// fail loudly per MockRestServer's own unmatched-request behavior.
		MockServerTestApi api = RIP.getClient(MockServerTestApi.class, server.baseUrl());

		String result = api.getOrder("abc123", "false");

		assertEquals("{\"status\":\"SHORT_CIRCUITED\"}", result);
		assertEquals(0, server.requestCount());
	}

	@Test
	void withoutAShortCircuitingInterceptor_stillReachesTheNetworkNormally() {
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{\"status\":\"CONFIRMED\"}"));
		MockServerTestApi api = RIP.getClient(MockServerTestApi.class, server.baseUrl());

		String result = api.getOrder("abc123", "false");

		assertEquals("{\"status\":\"CONFIRMED\"}", result);
		assertEquals(1, server.requestCount());
	}

}
