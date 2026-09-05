package com.shri.restinpeace.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.shri.restinpeace.RIP;
import com.shri.restinpeace.constant.HTTPMethod;

class MockRestServerExtensionTest {

	@RegisterExtension
	MockRestServerExtension serverExtension = new MockRestServerExtension();

	@Test
	void extension_startsAServerAvailableInTheTest() {
		MockRestServer server = serverExtension.getServer();
		server.on(HTTPMethod.GET, "/orders/{id}", MockResponse.ok("{\"status\":\"CONFIRMED\"}"));
		MockServerTestApi api = RIP.getClient(MockServerTestApi.class, server.baseUrl());

		String result = api.getOrder("abc123", "false");

		assertEquals("{\"status\":\"CONFIRMED\"}", result);
	}

	@Test
	void extension_givesAFreshServerPerTestMethod() {
		// If this were the same server instance the other test method used,
		// its route registration and recorded request would still be here -
		// JUnit creates a fresh test instance (and so a fresh extension field
		// and server) per test method by default, so it isn't.
		assertEquals(0, serverExtension.getServer().requestCount());
	}

}
