package com.shri.restinpeace.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.NoSuchElementException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shri.restinpeace.RIP;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.exception.RestInPeaceHttpException;

class MockRestServerTest {

	private MockRestServer server;
	private MockServerTestApi api;

	@BeforeEach
	void setUp() {
		server = MockRestServer.start();
		api = RIP.getClient(MockServerTestApi.class, server.baseUrl());
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	@Test
	void onRoute_matchesPathParamAndKeepsAnsweringEveryMatchingRequest() {
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{\"status\":\"CONFIRMED\"}"));

		String first = api.getOrder("abc123", "false");
		String second = api.getOrder("xyz789", "false");

		assertEquals("{\"status\":\"CONFIRMED\"}", first);
		assertEquals("{\"status\":\"CONFIRMED\"}", second);
		assertEquals(2, server.requestCount());
	}

	@Test
	void recordedRequest_exposesPathQueryHeadersAndBody() {
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{}"));

		api.getOrder("abc123", "true");

		RecordedRequest request = server.takeRequest();
		assertEquals(HTTPMethod.GET, request.getHttpMethod());
		assertEquals("/orders/abc123", request.getPath());
		assertEquals("true", request.getQueryParam("verbose"));
	}

	@Test
	void recordedRequest_exposesRequestBody() {
		server.on(HTTPMethod.POST, "/orders", MockResponse.ok("{\"orderId\":\"new-1\"}"));

		api.createOrder("{\"sku\":\"sku-1\",\"qty\":2}");

		RecordedRequest request = server.takeRequest();
		assertEquals("POST", request.getHttpMethod().name());
		assertTrue(request.getBody().contains("\"sku\":\"sku-1\""));
	}

	@Test
	void recordedRequest_exposesRequestHeaders() {
		server.on(HTTPMethod.GET, "/secure", MockResponse.ok("ok"));

		api.getSecure("Bearer test-token");

		RecordedRequest request = server.takeRequest();
		assertEquals("Bearer test-token", request.getHeader("Authorization"));
	}

	@Test
	void enqueue_thenRetryRecoversAfterAScriptedFailure() {
		server.enqueue(MockResponse.status(503, ""));
		server.enqueue(MockResponse.ok("{\"orderId\":\"new-1\"}"));

		String result = api.createOrder("{\"sku\":\"sku-1\"}");

		assertEquals("{\"orderId\":\"new-1\"}", result);
		assertEquals(2, server.requestCount());
	}

	@Test
	void unmatchedRequest_failsLoudlyInsteadOfSilentlySucceeding() {
		RestInPeaceHttpException exception = assertThrows(RestInPeaceHttpException.class,
				() -> api.getOrder("abc123", "false"));

		assertEquals(500, exception.getStatus());
		assertTrue(exception.getRawBody().contains("no response was queued or registered"));
	}

	@Test
	void takeRequest_onEmptyHistory_throwsNoSuchElementException() {
		assertThrows(NoSuchElementException.class, () -> server.takeRequest());
	}

}
