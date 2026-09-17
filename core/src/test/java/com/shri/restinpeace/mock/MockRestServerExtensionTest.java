package com.shri.restinpeace.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.shri.restinpeace.RIP;
import com.shri.restinpeace.constant.HTTPMethod;

@ExtendWith(MockRestServerExtension.class)
class MockRestServerExtensionTest {

	private static final List<String> baseUrlsSeen = new ArrayList<>();
	private static final List<Integer> requestCountsAtStart = new ArrayList<>();

	@BeforeEach
	void capture(MockRestServer server) {
		baseUrlsSeen.add(server.baseUrl());
		requestCountsAtStart.add(server.requestCount());
	}

	@Test
	void firstTest_registersARouteAndMakesARequest(MockRestServer server) {
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{\"status\":\"CONFIRMED\"}"));
		MockServerTestApi api = RIP.getClient(MockServerTestApi.class, server.baseUrl());

		String result = api.getOrder("abc123", "false");

		assertEquals("{\"status\":\"CONFIRMED\"}", result);
	}

	@Test
	void secondTest_seesNoStateFromTheFirst(MockRestServer server) {
		assertEquals(0, server.requestCount());
	}

	@Test
	void thirdTest_withAnotherParameterAlongsideTheServer_resolvesBothCorrectly(MockRestServer server,
			org.junit.jupiter.api.TestInfo testInfo) {
		assertEquals(0, server.requestCount());
		assertEquals("thirdTest_withAnotherParameterAlongsideTheServer_resolvesBothCorrectly", testInfo.getTestMethod()
				.map(java.lang.reflect.Method::getName).orElse(null));
	}

	@AfterAll
	static void allTestsSharedTheSameServerAndStartedWithResetState() {
		assertEquals(3, baseUrlsSeen.size());
		// Same base URL for every test proves beforeAll started the server
		// once, not once per test.
		assertEquals(baseUrlsSeen.get(0), baseUrlsSeen.get(1));
		assertEquals(baseUrlsSeen.get(0), baseUrlsSeen.get(2));
		// Zero requests at the start of every test proves beforeEach reset the
		// shared server, not that each got a brand-new one.
		assertEquals(0, (int) requestCountsAtStart.get(0));
		assertEquals(0, (int) requestCountsAtStart.get(1));
		assertEquals(0, (int) requestCountsAtStart.get(2));
	}

}
