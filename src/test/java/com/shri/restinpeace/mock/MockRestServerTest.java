package com.shri.restinpeace.mock;

import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import kong.unirest.JsonObjectMapper;
import kong.unirest.UnirestException;

import com.shri.restinpeace.RIP;
import com.shri.restinpeace.RipResponse;
import com.shri.restinpeace.constant.HTTPMethod;
import com.shri.restinpeace.exception.RestInPeaceHttpException;

class MockRestServerTest {

	private MockRestServer server;
	private MockServerTestApi api;

	@BeforeEach
	void setUp() {
		RIP.setObjectMapper(new JsonObjectMapper());
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

	@Test
	void mockResponseJson_serializesAnObjectUsingTheConfiguredObjectMapper() {
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.json(new OrderStatus("CONFIRMED")));

		String result = api.getOrder("abc123", "false");

		assertTrue(result.contains("CONFIRMED"), "Expected the serialized status in: " + result);
	}

	@Test
	void reset_clearsQueuedResponsesRoutesAndRecordedRequests() {
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{}"));
		api.getOrder("abc123", "false");
		assertEquals(1, server.requestCount());

		server.reset();

		assertEquals(0, server.requestCount());
		assertThrows(RestInPeaceHttpException.class, () -> api.getOrder("abc123", "false"));
	}

	@Test
	void onRouteWithQueryParams_onlyMatchesWhenTheyreEqual() {
		server.on(HTTPMethod.GET, "/orders/{id}", singletonMap("verbose", "true"), MockResponse.ok("{\"detail\":\"full\"}"));
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{\"detail\":\"summary\"}"));

		String verbose = api.getOrder("abc123", "true");
		String summary = api.getOrder("abc123", "false");

		assertEquals("{\"detail\":\"full\"}", verbose);
		assertEquals("{\"detail\":\"summary\"}", summary);
	}

	@Test
	void header_calledTwiceForTheSameNameSendsBothValues() {
		server.on(HTTPMethod.GET, "/with-headers",
				MockResponse.ok("{}").header("Set-Cookie", "a=1").header("Set-Cookie", "b=2"));

		RipResponse<String> response = api.getWithHeaders();

		assertEquals(Arrays.asList("a=1", "b=2"), response.getHeaders().get("Set-Cookie"));
	}

	@Test
	void connectionFailure_throwsUnirestExceptionInsteadOfAnHttpStatus() {
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.connectionFailure());

		assertThrows(UnirestException.class, () -> api.getOrder("abc123", "false"));
	}

	@Test
	void connectionFailure_isAlwaysRetriedRegardlessOfRetryOnStatus() {
		server.enqueue(MockResponse.connectionFailure());
		server.enqueue(MockResponse.ok("{\"orderId\":\"new-1\"}"));

		String result = api.createOrder("{\"sku\":\"sku-1\"}");

		assertEquals("{\"orderId\":\"new-1\"}", result);
		assertEquals(2, server.requestCount());
	}

	@Test
	void delay_shorterThanTimeout_succeeds() {
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{}").delay(10));

		String result = api.getOrderWithShortTimeout("abc123");

		assertEquals("{}", result);
	}

	@Test
	void delay_longerThanTimeout_throws() {
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{}").delay(500));

		assertThrows(RuntimeException.class, () -> api.getOrderWithShortTimeout("abc123"));
	}

	@Test
	void enqueueFor_withoutOn_throwsNoSuchElementException() {
		assertThrows(NoSuchElementException.class,
				() -> server.enqueueFor(HTTPMethod.GET, "/orders/{id}", MockResponse.status(503, "")));
	}

	@Test
	void enqueueFor_scriptsResponsesBeforeTheRoutesStickyResponseTakesOver() {
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{\"status\":\"CONFIRMED\"}"));
		server.enqueueFor(HTTPMethod.GET, "/orders/{id}", MockResponse.status(503, ""));
		server.enqueueFor(HTTPMethod.GET, "/orders/{id}", MockResponse.status(503, ""));

		RestInPeaceHttpException first = assertThrows(RestInPeaceHttpException.class,
				() -> api.getOrder("abc123", "false"));
		RestInPeaceHttpException second = assertThrows(RestInPeaceHttpException.class,
				() -> api.getOrder("abc123", "false"));
		String third = api.getOrder("abc123", "false");
		String fourth = api.getOrder("abc123", "false");

		assertEquals(503, first.getStatus());
		assertEquals(503, second.getStatus());
		assertEquals("{\"status\":\"CONFIRMED\"}", third);
		assertEquals("{\"status\":\"CONFIRMED\"}", fourth);
	}

	@Test
	void onFlaky_failsGivenTimesThenSucceedsSticky() {
		server.onFlaky(HTTPMethod.GET, "/orders/{id}", 2, MockResponse.status(503, ""),
				MockResponse.ok("{\"status\":\"CONFIRMED\"}"));

		assertThrows(RestInPeaceHttpException.class, () -> api.getOrder("abc123", "false"));
		assertThrows(RestInPeaceHttpException.class, () -> api.getOrder("abc123", "false"));
		assertEquals("{\"status\":\"CONFIRMED\"}", api.getOrder("abc123", "false"));
		assertEquals("{\"status\":\"CONFIRMED\"}", api.getOrder("abc123", "false"));
	}

	@Test
	void onFlaky_thenRetryRecoversInOneClientCall() {
		server.onFlaky(HTTPMethod.POST, "/orders", 2, MockResponse.status(503, ""),
				MockResponse.ok("{\"orderId\":\"new-1\"}"));

		String result = api.createOrder("{\"sku\":\"sku-1\"}");

		assertEquals("{\"orderId\":\"new-1\"}", result);
		assertEquals(3, server.requestCount());
	}

	@Test
	void on_calledTwiceForTheSameRoute_replacesInsteadOfShadowing() {
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{\"status\":\"PENDING\"}"));
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{\"status\":\"CONFIRMED\"}"));

		String result = api.getOrder("abc123", "false");

		assertEquals("{\"status\":\"CONFIRMED\"}", result);
	}

	@Test
	void remove_removesARegisteredRouteWithoutAffectingOthers() {
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{}"));
		server.on(HTTPMethod.GET, "/secure", MockResponse.ok("ok"));

		boolean removed = server.remove(HTTPMethod.GET, "/orders/{id}");

		assertTrue(removed);
		assertThrows(RestInPeaceHttpException.class, () -> api.getOrder("abc123", "false"));
		assertEquals("ok", api.getSecure("Bearer test-token"));
	}

	@Test
	void remove_ofAnUnregisteredRoute_returnsFalse() {
		boolean removed = server.remove(HTTPMethod.GET, "/orders/{id}");

		assertFalse(removed);
	}

	@Test
	void remove_doesNotWipeRecordedRequestHistory() {
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{}"));
		api.getOrder("abc123", "false");

		server.remove(HTTPMethod.GET, "/orders/{id}");

		assertEquals(1, server.requestCount());
	}

	@Test
	void onRouteWithMatcher_onlyMatchesWhenTheHeaderPredicateIsTrue() {
		server.on(HTTPMethod.GET, "/secure", request -> "Bearer good-token".equals(request.getHeader("Authorization")),
				MockResponse.ok("granted"));
		server.on(HTTPMethod.GET, "/secure", MockResponse.status(403, "denied"));

		String granted = api.getSecure("Bearer good-token");
		RestInPeaceHttpException denied = assertThrows(RestInPeaceHttpException.class,
				() -> api.getSecure("Bearer bad-token"));

		assertEquals("granted", granted);
		assertEquals(403, denied.getStatus());
	}

	@Test
	void onRouteWithMatcher_canMatchOnBodyContent() {
		server.on(HTTPMethod.POST, "/orders", request -> request.getBody().contains("premium"),
				MockResponse.ok("{\"orderId\":\"premium-1\"}"));
		server.on(HTTPMethod.POST, "/orders", MockResponse.ok("{\"orderId\":\"standard-1\"}"));

		String premium = api.createOrder("{\"sku\":\"premium-item\"}");
		String standard = api.createOrder("{\"sku\":\"basic-item\"}");

		assertEquals("{\"orderId\":\"premium-1\"}", premium);
		assertEquals("{\"orderId\":\"standard-1\"}", standard);
	}

	private static final class OrderStatus {
		@SuppressWarnings("unused")
		public final String status;

		OrderStatus(String status) {
			this.status = status;
		}
	}

}
